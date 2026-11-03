"""Allowlisted, low-sensitivity device diagnostics forwarded by the server."""

from typing import Any, Dict


DEVICE_INFO_FIELDS = {
    "manufacturer",
    "model",
    "platform",
    "os_version",
    "android_version",
    "app_version",
    "battery_percent",
    "free_ram_mb",
    "free_storage_mb",
    "wifi_enabled",
    "network_type",
    "app_status",
}
NETWORK_TYPES = {"wifi", "mobile", "ethernet", "none", "unknown"}
APP_STATES = {"ready", "connected", "streaming", "error", "unknown"}
DIAGNOSTIC_NAMES = {
    "internet",
    "server_connection",
    "screen_capture_permission",
    "accessibility_permission",
    "storage",
    "wifi",
    "foreground_service",
}
DIAGNOSTIC_STATUSES = {"ok", "warning", "error", "unsupported", "unknown"}


def sanitize_payload(message_type: str, payload: Any) -> Dict[str, Any]:
    if not isinstance(payload, dict):
        raise ValueError("Payload must be a JSON object")
    if message_type in {"offer", "answer"}:
        sdp = payload.get("sdp")
        if not isinstance(sdp, str) or not sdp or len(sdp) > 24000:
            raise ValueError("SDP payload must contain a valid sdp string")
        return {"sdp": sdp}
    if message_type == "ice_candidate":
        candidate = payload.get("candidate")
        if not isinstance(candidate, str) or not candidate or len(candidate) > 4096:
            raise ValueError("ICE payload must contain a valid candidate string")
        safe: Dict[str, Any] = {"candidate": candidate}
        mid = payload.get("sdp_mid")
        index = payload.get("sdp_mline_index")
        if mid is not None:
            if not isinstance(mid, str) or len(mid) > 128:
                raise ValueError("sdp_mid must be a short string")
            safe["sdp_mid"] = mid
        if index is not None:
            if not isinstance(index, int) or isinstance(index, bool) or index < 0:
                raise ValueError("sdp_mline_index must be a non-negative integer")
            safe["sdp_mline_index"] = index
        return safe
    if message_type in {"screen_share_ready", "screen_share_stopped"}:
        return {}
    if message_type == "device_info":
        return _sanitize_device_info(payload)
    if message_type == "diagnostics":
        return _sanitize_diagnostics(payload)
    return {}


def _sanitize_device_info(payload: Dict[str, Any]) -> Dict[str, Any]:
    result: Dict[str, Any] = {}
    for key in DEVICE_INFO_FIELDS:
        if key not in payload:
            continue
        value = payload[key]
        if key in {"manufacturer", "model", "platform", "os_version", "android_version", "app_version"}:
            if isinstance(value, str):
                result[key] = value[:100]
        elif key in {"battery_percent", "free_ram_mb", "free_storage_mb"}:
            if isinstance(value, int) and not isinstance(value, bool) and value >= 0:
                result[key] = min(value, 10_000_000)
        elif key == "wifi_enabled" and isinstance(value, bool):
            result[key] = value
        elif key == "network_type" and isinstance(value, str) and value in NETWORK_TYPES:
            result[key] = value
        elif key == "app_status" and isinstance(value, str) and value in APP_STATES:
            result[key] = value
    return result


def _sanitize_diagnostics(payload: Dict[str, Any]) -> Dict[str, Any]:
    checks = payload.get("checks")
    if not isinstance(checks, list):
        raise ValueError("Diagnostics payload must contain a checks array")
    safe_checks = []
    for check in checks[:32]:
        if not isinstance(check, dict):
            continue
        name, status = check.get("name"), check.get("status")
        if isinstance(name, str) and isinstance(status, str) and name in DIAGNOSTIC_NAMES and status in DIAGNOSTIC_STATUSES:
            safe_checks.append({"name": name, "status": status})
    return {"checks": safe_checks}
