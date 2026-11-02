"""WebSocket message validation and role-specific signaling allowlists."""

from typing import Any, Dict, Optional


TECHNICIAN_ACTIVE_TYPES = {"offer", "ice_candidate"}
CLIENT_ACTIVE_TYPES = {
    "answer",
    "ice_candidate",
    "device_info",
    "diagnostics",
    "screen_share_ready",
    "screen_share_stopped",
}


def validate_signal(message: Dict[str, Any], role: str, active: bool) -> Optional[str]:
    message_type = message.get("type")
    if not isinstance(message_type, str):
        return "Message type is required"
    if role == "client" and message_type == "consent":
        if not isinstance(message.get("approved"), bool):
            return "Consent must include a boolean approved value"
        return None
    if message_type == "disconnect":
        return None
    allowed = TECHNICIAN_ACTIVE_TYPES if role == "technician" else CLIENT_ACTIVE_TYPES
    if not active:
        return "Only consent is allowed before the client approves"
    if message_type not in allowed:
        return "Unsupported message type"
    if message_type != "disconnect" and "payload" in message and not isinstance(message["payload"], dict):
        return "Payload must be a JSON object"
    return None
