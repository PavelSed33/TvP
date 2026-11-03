import unittest
import time

from server.app.diagnostics import sanitize_payload
from server.app.security import SlidingWindowLimiter
from server.app.sessions import SessionStore
from server.app.signaling import validate_signal


class SessionStoreTests(unittest.TestCase):
    def setUp(self):
        self.store = SessionStore(code_ttl_seconds=300, session_ttl_seconds=1800)

    def test_creates_six_digit_code_and_separate_client_token(self):
        session = self.store.create()
        self.assertRegex(session.code, r"^\d{6}$")
        self.assertGreaterEqual(len(session.client_token), 40)
        self.assertNotEqual(session.code, session.client_token)

    def test_claiming_code_makes_it_unavailable(self):
        session = self.store.create()
        self.assertIs(self.store.get_by_code(session.code), session)
        self.assertTrue(self.store.attach_technician(session, object()))
        self.assertIsNone(self.store.get_by_code(session.code))
        self.assertFalse(self.store.attach_technician(session, object()))

    def test_client_token_is_bound_to_its_session(self):
        session = self.store.create()
        self.assertTrue(self.store.authenticate_client(session, session.client_token))
        self.assertFalse(self.store.authenticate_client(session, "wrong-token"))
        self.assertFalse(self.store.authenticate_client(session, "ключ"))

    def test_expired_session_is_not_found_by_code(self):
        session = self.store.create()
        session.deadline = time.monotonic() - 1
        self.assertTrue(self.store.is_expired(session))
        # Expired codes are removed as soon as looked up.
        self.assertIsNone(self.store.get_by_code(session.code))


class SecurityTests(unittest.TestCase):
    def test_sliding_window_limiter(self):
        limiter = SlidingWindowLimiter(limit=2, window_seconds=60)
        self.assertTrue(limiter.allow("peer", now=100))
        self.assertTrue(limiter.allow("peer", now=110))
        self.assertFalse(limiter.allow("peer", now=120))
        self.assertTrue(limiter.allow("peer", now=161))

    def test_diagnostics_drop_unknown_and_personal_fields(self):
        payload = sanitize_payload("device_info", {
            "manufacturer": "Example",
            "model": "TV 1",
            "platform": "Tizen",
            "os_version": "8.0",
            "battery_percent": 87,
            "ip_address": "192.0.2.1",
            "photos": ["private"],
        })
        self.assertEqual(payload, {
            "manufacturer": "Example",
            "model": "TV 1",
            "platform": "Tizen",
            "os_version": "8.0",
            "battery_percent": 87,
        })

    def test_diagnostics_reject_unstructured_text(self):
        with self.assertRaises(ValueError):
            sanitize_payload("diagnostics", {"checks": "client private messages"})

    def test_signaling_is_blocked_until_client_consent(self):
        offer = {"type": "offer", "payload": {"sdp": "v=0"}}
        self.assertEqual(validate_signal(offer, "technician", active=False), "Only consent is allowed before the client approves")
        self.assertIsNone(validate_signal(offer, "technician", active=True))
        self.assertIsNone(validate_signal({"type": "disconnect"}, "technician", active=False))


if __name__ == "__main__":
    unittest.main()
