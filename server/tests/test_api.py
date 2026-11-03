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
    def setUp(self):
        self.original_limiters = (
            server_main.creation_limiter,
            server_main.join_limiter,
            server_main.signaling_limiter,
        )
        server_main.creation_limiter = SlidingWindowLimiter(server_main.settings.create_limit_per_minute)
        server_main.join_limiter = SlidingWindowLimiter(server_main.settings.join_limit_per_minute)
        server_main.signaling_limiter = SlidingWindowLimiter(server_main.settings.signal_limit_per_minute)

    def tearDown(self):
        (
            server_main.creation_limiter,
            server_main.join_limiter,
            server_main.signaling_limiter,
        ) = self.original_limiters

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
                    request = client.receive_json()
                    self.assertEqual(request["type"], "connection_request")
                    self.assertEqual(request["expires_at"], joined["expires_at"])

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

    def test_client_receives_session_expiry_when_it_connects_after_technician(self):
        with TestClient(app) as api:
            session = api.post("/api/sessions").json()
            with api.websocket_connect("/ws/technician") as technician:
                technician.send_json({"type": "join", "code": session["code"]})
                joined = technician.receive_json()
                self.assertEqual(joined["type"], "joined")

                with api.websocket_connect("/ws/client/" + session["session_id"]) as client:
                    client.send_json({"type": "authenticate", "token": session["client_token"]})
                    self.assertEqual(client.receive_json()["type"], "authenticated")
                    request = client.receive_json()
                    self.assertEqual(request["type"], "connection_request")
                    self.assertEqual(request["expires_at"], joined["expires_at"])

    def test_invalid_client_token_does_not_consume_the_session(self):
        with TestClient(app) as api:
            session = api.post("/api/sessions").json()
            with api.websocket_connect("/ws/client/" + session["session_id"]) as rejected:
                rejected.send_json({"type": "authenticate", "token": "wrong-token"})
                self.assertEqual(rejected.receive_json()["type"], "error")

            with api.websocket_connect("/ws/client/" + session["session_id"]) as accepted:
                accepted.send_json({"type": "authenticate", "token": session["client_token"]})
                self.assertEqual(accepted.receive_json()["type"], "authenticated")

    def test_duplicate_client_connection_cannot_replace_the_authenticated_client(self):
        with TestClient(app) as api:
            session = api.post("/api/sessions").json()
            with api.websocket_connect("/ws/client/" + session["session_id"]) as first:
                first.send_json({"type": "authenticate", "token": session["client_token"]})
                self.assertEqual(first.receive_json()["type"], "authenticated")
                original_socket = server_main.sessions.get_by_id(session["session_id"]).client_ws

                with api.websocket_connect("/ws/client/" + session["session_id"]) as second:
                    second.send_json({"type": "authenticate", "token": session["client_token"]})
                    self.assertEqual(second.receive_json()["type"], "error")

                self.assertIsNotNone(server_main.sessions.get_by_id(session["session_id"]))
                self.assertIs(server_main.sessions.get_by_id(session["session_id"]).client_ws, original_socket)

    def test_oversized_websocket_message_is_rejected_and_session_is_removed(self):
        with TestClient(app) as api:
            session = api.post("/api/sessions").json()
            with api.websocket_connect("/ws/client/" + session["session_id"]) as client:
                client.send_json({"type": "authenticate", "token": session["client_token"]})
                self.assertEqual(client.receive_json()["type"], "authenticated")
                client.send_text("x" * (server_main.settings.max_message_bytes + 1))
                self.assertEqual(client.receive_json()["type"], "error")

            self.assertIsNone(server_main.sessions.get_by_id(session["session_id"]))

    def test_join_rate_limit_rejects_excessive_code_attempts(self):
        original_limiter = server_main.join_limiter
        server_main.join_limiter = SlidingWindowLimiter(2)
        try:
            with TestClient(app) as api:
                responses = []
                for _ in range(3):
                    with api.websocket_connect("/ws/technician") as technician:
                        technician.send_json({"type": "join", "code": "000000"})
                        responses.append(technician.receive_json()["message"])
                self.assertIn("invalid, expired, or already used", responses[0])
                self.assertIn("invalid, expired, or already used", responses[1])
                self.assertIn("Too many code attempts", responses[2])
        finally:
            server_main.join_limiter = original_limiter

    def test_client_denial_terminates_both_websockets_and_removes_session(self):
        with self.assertLogs("remotefix.security", level="INFO") as audit:
            with TestClient(app) as api:
                session = api.post("/api/sessions").json()
                with api.websocket_connect("/ws/client/" + session["session_id"]) as client:
                    client.send_json({"type": "authenticate", "token": session["client_token"]})
                    self.assertEqual(client.receive_json()["type"], "authenticated")

                    with api.websocket_connect("/ws/technician") as technician:
                        technician.send_json({"type": "join", "code": session["code"]})
                        self.assertEqual(technician.receive_json()["type"], "joined")
                        self.assertEqual(client.receive_json()["type"], "connection_request")
                        client.send_json({"type": "consent", "approved": False})
                        self.assertEqual(client.receive_json()["type"], "session_ended")
                        self.assertEqual(technician.receive_json(), {"type": "consent_result", "approved": False})
                        self.assertEqual(technician.receive_json()["type"], "session_ended")

                self.assertIsNone(server_main.sessions.get_by_id(session["session_id"]))

        denial_events = [line for line in audit.output if "security_event=consent_denied" in line]
        self.assertEqual(len(denial_events), 1)

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
