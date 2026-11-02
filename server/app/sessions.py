"""One-time connection codes and in-memory session lifecycle."""

from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
import secrets
import time
from typing import Any, Dict, Optional

from .security import new_client_token, new_connection_code, new_session_id


@dataclass
class RemoteSession:
    session_id: str
    code: str
    client_token: str
    created_at: datetime
    expires_at: datetime
    deadline: float
    client_ws: Optional[Any] = None
    technician_ws: Optional[Any] = None
    state: str = "waiting_for_technician"


class SessionStore:
    """Ephemeral store for a single API process; it deliberately has no persistence."""

    def __init__(self, code_ttl_seconds: int, session_ttl_seconds: int) -> None:
        self.code_ttl_seconds = code_ttl_seconds
        self.session_ttl_seconds = session_ttl_seconds
        self.by_id: Dict[str, RemoteSession] = {}
        self.by_code: Dict[str, str] = {}

    def create(self) -> RemoteSession:
        now = datetime.now(timezone.utc)
        code = self._unique_code()
        session = RemoteSession(
            session_id=new_session_id(),
            code=code,
            client_token=new_client_token(),
            created_at=now,
            expires_at=now + timedelta(seconds=self.code_ttl_seconds),
            deadline=time.monotonic() + self.code_ttl_seconds,
        )
        self.by_id[session.session_id] = session
        self.by_code[code] = session.session_id
        return session

    def get_by_id(self, session_id: str) -> Optional[RemoteSession]:
        return self.by_id.get(session_id)

    def get_by_code(self, code: str) -> Optional[RemoteSession]:
        session_id = self.by_code.get(code)
        session = self.by_id.get(session_id) if session_id else None
        if session and self.is_expired(session):
            self.remove(session.session_id)
            return None
        return session

    def authenticate_client(self, session: RemoteSession, token: str) -> bool:
        return isinstance(token, str) and token.isascii() and secrets.compare_digest(session.client_token, token)

    def attach_client(self, session: RemoteSession, websocket: Any) -> bool:
        if session.client_ws is not None or self.is_expired(session):
            return False
        session.client_ws = websocket
        if session.technician_ws:
            session.state = "awaiting_consent"
        return True

    def attach_technician(self, session: RemoteSession, websocket: Any) -> bool:
        if session.technician_ws is not None or self.is_expired(session):
            return False
        session.technician_ws = websocket
        self.by_code.pop(session.code, None)  # The code is single-use once a technician claims it.
        session.deadline = time.monotonic() + self.session_ttl_seconds
        session.expires_at = datetime.now(timezone.utc) + timedelta(seconds=self.session_ttl_seconds)
        session.state = "awaiting_consent" if session.client_ws else "awaiting_client"
        return True

    def set_active(self, session: RemoteSession) -> None:
        session.state = "active"

    def is_expired(self, session: RemoteSession, now: float = None) -> bool:
        return (time.monotonic() if now is None else now) >= session.deadline

    def expired_ids(self) -> list:
        now = time.monotonic()
        return [session_id for session_id, session in self.by_id.items() if self.is_expired(session, now)]

    def remove(self, session_id: str) -> Optional[RemoteSession]:
        session = self.by_id.pop(session_id, None)
        if session:
            self.by_code.pop(session.code, None)
            session.state = "closed"
        return session

    def detach(self, session: RemoteSession, websocket: Any) -> None:
        if session.client_ws is websocket:
            session.client_ws = None
        if session.technician_ws is websocket:
            session.technician_ws = None

    def _unique_code(self) -> str:
        for _ in range(100):
            code = new_connection_code()
            if code not in self.by_code:
                return code
        raise RuntimeError("Could not allocate a unique connection code")
