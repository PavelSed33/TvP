"""Environment-backed settings for the single-process RemoteFix server."""

from dataclasses import dataclass
import os


def _int_env(name: str, default: int, minimum: int = 1) -> int:
    value = os.getenv(name)
    if value is None:
        return default
    try:
        parsed = int(value)
    except ValueError as exc:
        raise ValueError(f"{name} must be an integer") from exc
    if parsed < minimum:
        raise ValueError(f"{name} must be at least {minimum}")
    return parsed


def _csv_env(name: str, default: str) -> tuple[str, ...]:
    value = os.getenv(name, default)
    return tuple(item.strip() for item in value.split(",") if item.strip())


@dataclass(frozen=True)
class Settings:
    code_ttl_seconds: int = 300
    session_ttl_seconds: int = 1800
    create_limit_per_minute: int = 5
    join_limit_per_minute: int = 12
    max_message_bytes: int = 32768
    signal_limit_per_minute: int = 180
    client_auth_timeout_seconds: int = 8
    technician_auth_timeout_seconds: int = 8
    stun_urls: tuple[str, ...] = ("stun:stun.l.google.com:19302",)
    turn_urls: tuple[str, ...] = ()
    turn_shared_secret: str = ""
    turn_credential_ttl_seconds: int = 3600

    @classmethod
    def from_env(cls) -> "Settings":
        turn_urls = _csv_env("REMOTEFIX_TURN_URLS", "")
        stun_urls = _csv_env("REMOTEFIX_STUN_URLS", "stun:stun.l.google.com:19302")
        turn_shared_secret = os.getenv("REMOTEFIX_TURN_SHARED_SECRET", "")
        if any(not url.startswith(("stun:", "stuns:")) for url in stun_urls):
            raise ValueError("REMOTEFIX_STUN_URLS entries must use stun: or stuns:")
        if any(not url.startswith(("turn:", "turns:")) for url in turn_urls):
            raise ValueError("REMOTEFIX_TURN_URLS entries must use turn: or turns:")
        if turn_urls and len(turn_shared_secret) < 32:
            raise ValueError("REMOTEFIX_TURN_SHARED_SECRET must contain at least 32 characters when TURN is enabled")
        return cls(
            code_ttl_seconds=_int_env("REMOTEFIX_CODE_TTL_SECONDS", 300),
            session_ttl_seconds=_int_env("REMOTEFIX_SESSION_TTL_SECONDS", 1800),
            create_limit_per_minute=_int_env("REMOTEFIX_CREATE_LIMIT_PER_MINUTE", 5),
            join_limit_per_minute=_int_env("REMOTEFIX_JOIN_LIMIT_PER_MINUTE", 12),
            max_message_bytes=_int_env("REMOTEFIX_MAX_MESSAGE_BYTES", 32768),
            signal_limit_per_minute=_int_env("REMOTEFIX_SIGNAL_LIMIT_PER_MINUTE", 180),
            client_auth_timeout_seconds=_int_env("REMOTEFIX_CLIENT_AUTH_TIMEOUT_SECONDS", 8),
            technician_auth_timeout_seconds=_int_env("REMOTEFIX_TECH_AUTH_TIMEOUT_SECONDS", 8),
            stun_urls=stun_urls,
            turn_urls=turn_urls,
            turn_shared_secret=turn_shared_secret,
            turn_credential_ttl_seconds=_int_env("REMOTEFIX_TURN_CREDENTIAL_TTL_SECONDS", 3600),
        )
