import base64
import hashlib
import hmac
import unittest
from dataclasses import replace
from datetime import datetime

from fastapi.testclient import TestClient

import server.app.main as server_main
from server.app.main import app
from server.app.security import SlidingWindowLimiter


class ApiFlowTests(unittest.TestCase):
    def test_health_and_panel_are_served(self):
        with TestClient(app) as client:
            self.assertEqual(client.get("/api/health").json(), {"status": "ok", "stage": "3-webrtc-in-progress"})
            response = client.get("/")
            self.assertEqual(response.status_code, 200)
            self.assertIn("RemoteFix", response.text)

    def test_client_consent_gates_allowlisted_signaling(self):
        with TestClient(app) as api:
            created = api.post("/api/sessions")
            self.assertEqual(created.status_code, 201)
            session = created.json()

            with api.websocket_connect("/ws/client/" + session["session_id"]) as client:
                client.send_json({"type": "authenticate", "token": session["client_token"]})
                self.assertEqual(client.receive_json()["type"], "authenticated")

                with api.websocket_connect("/ws/technician") as technician:
                    technician.send_json({"type": "join", "code": session["code"]})
                    joined = technician.receive_json()
                    self.assertEqual(joined["type"], "joined")
                    self.assertTrue(joined["ice_servers"])
                    self.assertEqual(client.receive_json()["type"], "connection_request")

                    client.send_json({"type": "consent", "approved": True})
                    self.assertTrue(client.receive_json()["approved"])
                    self.assertTrue(technician.receive_json()["approved"])

                    client.send_json({"type": "screen_share_ready"})
                    self.assertEqual(technician.receive_json(), {"type": "screen_share_ready", "payload": {}})

                    technician.send_json({"type": "offer", "payload": {"sdp": "v=0"}})
                    self.assertEqual(client.receive_json(), {"type": "offer", "payload": {"sdp": "v=0"}})

                    client.send_json({"type": "screen_share_stopped"})
                    self.assertEqual(technician.receive_json(), {"type": "screen_share_stopped", "payload": {}})

                    client.send_json({
                        "type": "device_info",
                        "payload": {"manufacturer": "Example", "model": "Phone", "ip_address": "192.0.2.10"},
                    })
                    self.assertEqual(technician.receive_json(), {
                        "type": "device_info",
                        "payload": {"manufacturer": "Example", "model": "Phone"},
                    })

                    technician.send_json({"type": "disconnect"})
                    self.assertEqual(client.receive_json()["type"], "session_ended")

    def test_turn_credentials_are_short_lived_and_session_scoped(self):
        original_settings = server_main.settings
        shared_secret = "0123456789abcdef0123456789abcdef"
        server_main.settings = replace(
            original_settings,
            turn_urls=("turn:turn.example.test:3478?transport=udp",),
            turn_shared_secret=shared_secret,
            turn_credential_ttl_seconds=60,
        )
        try:
            with TestClient(app) as client:
                response = client.post("/api/sessions")
            self.assertEqual(response.status_code, 201)
            session = response.json()
            turn = next(server for server in session["ice_servers"] if server["urls"][0].startswith("turn:"))
            expires, opaque_session_id = turn["username"].split(":", 1)
            expected = base64.b64encode(
                hmac.new(shared_secret.encode(), turn["username"].encode(), hashlib.sha1).digest(),
            ).decode("ascii")
            self.assertEqual(turn["credential"], expected)
            self.assertEqual(opaque_session_id, session["session_id"])
            self.assertGreater(int(expires), 0)
            self.assertLessEqual(int(expires), int(datetime.fromisoformat(session["expires_at"]).timestamp()))
        finally:
            server_main.settings = original_settings

    def test_webrtc_signaling_is_rate_limited_per_session_participant(self):
        original_limiter = server_main.signaling_limiter
        server_main.signaling_limiter = SlidingWindowLimiter(1)
        try:
            with TestClient(app) as api:
                session = api.post("/api/sessions").json()
                with api.websocket_connect("/ws/client/" + session["session_id"]) as client:
                    client.send_json({"type": "authenticate", "token": session["client_token"]})
                    client.receive_json()
                    with api.websocket_connect("/ws/technician") as technician:
                        technician.send_json({"type": "join", "code": session["code"]})
                        technician.receive_json()
                        client.receive_json()
                        client.send_json({"type": "consent", "approved": True})
                        client.receive_json()
                        technician.receive_json()

                        offer = {"type": "offer", "payload": {"sdp": "v=0"}}
                        technician.send_json(offer)
                        self.assertEqual(client.receive_json(), offer)
                        technician.send_json(offer)
                        self.assertEqual(technician.receive_json()["type"], "error")
        finally:
            server_main.signaling_limiter = original_limiter


if __name__ == "__main__":
    unittest.main()
