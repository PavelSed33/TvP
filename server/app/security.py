"""Small security primitives used by the in-memory stage-one deployment."""

from collections import defaultdict, deque
import secrets
import time
from typing import Deque, Dict


def new_client_token() -> str:
    return secrets.token_urlsafe(32)


def new_session_id() -> str:
    return secrets.token_urlsafe(18)


def new_connection_code() -> str:
    return f"{secrets.randbelow(1_000_000):06d}"


class SlidingWindowLimiter:
    """Process-local request limiter. Use a shared store when scaling beyond one worker."""

    def __init__(self, limit: int, window_seconds: int = 60) -> None:
        self.limit = limit
        self.window_seconds = window_seconds
        self._events: Dict[str, Deque[float]] = defaultdict(deque)
        self._calls = 0

    def allow(self, key: str, now: float = None) -> bool:
        current = time.monotonic() if now is None else now
        events = self._events[key]
        cutoff = current - self.window_seconds
        while events and events[0] <= cutoff:
            events.popleft()
        self._calls += 1
        if self._calls % 256 == 0:
            stale = [peer for peer, timestamps in self._events.items() if peer != key and (not timestamps or timestamps[-1] <= cutoff)]
            for peer in stale:
                self._events.pop(peer, None)
        if len(events) >= self.limit:
            return False
        events.append(current)
        return True
