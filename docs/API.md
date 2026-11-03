# Session and signaling API

All timestamps are UTC ISO 8601 strings. In deployment, serve these routes only through HTTPS/WSS.

## Health

`GET /api/health` → `{"status":"ok","stage":"3-webrtc-in-progress"}`. The stage remains in progress until a real Android-to-browser stream is verified.

## Create session

`POST /api/sessions` with no body returns `201`:

```json
{
  "session_id": "opaque-id",
  "code": "123456",
  "client_token": "one-time-client-bearer-token",
  "expires_at": "2026-11-02T12:00:00+00:00",
  "code_ttl_seconds": 300,
  "ice_servers": [
    {"urls": ["stun:stun.l.google.com:19302"]},
    {"urls": ["turn:turn.example.com:3478?transport=udp"], "username": "<expiry>:<session>", "credential": "<temporary-HMAC>"}
  ]
}
```

The code is six decimal digits, valid for five minutes by default, and removed when claimed. The token authenticates the Android client WebSocket and must not be shown to the technician. Creation is limited to five requests per minute per peer address by default. Each peer obtains short-lived TURN credentials when `REMOTEFIX_TURN_URLS` and `REMOTEFIX_TURN_SHARED_SECRET` are configured; the permanent secret is never returned.

## Client WebSocket

Connect to `/ws/client/{session_id}`. First frame:

```json
{"type":"authenticate","token":"client_token from the create response"}
```

The server replies `authenticated`. If a technician has claimed the code, the client receives `connection_request` and must answer:

```json
{"type":"consent","approved":true}
```

or deny with `approved:false`. The support-session consent and Android's MediaProjection consent are separate. The client starts a media-projection foreground service only after its owner approves the Android system capture prompt.

After support consent, the client may send `answer`, `ice_candidate`, `device_info`, `diagnostics`, `screen_share_ready`, `screen_share_stopped`, or `disconnect`. `screen_share_ready` and `screen_share_stopped` contain no user data. Disconnect is allowed before approval too.

## Technician WebSocket

Connect to `/ws/technician`. First frame:

```json
{"type":"join","code":"123456"}
```

The server sends `joined` with a state, expiry, and the session's ICE server list. After approval the technician may send `offer`, `ice_candidate`, or `disconnect`. The panel sends a `recvonly` video offer only after Android reports that the separately approved capture service is ready.

## Relay payloads

- `offer` and `answer`: `payload.sdp` string, up to 24 KB.
- `ice_candidate`: `payload.candidate`, optional `sdp_mid`, optional non-negative `sdp_mline_index`.
- `device_info`: only manufacturer, model, platform/OS version, Android/app version, battery percentage, free RAM/storage, Wi-Fi flag, network type, and app status are forwarded.
- `diagnostics`: `payload.checks` entries with an allowlisted check name and status. Free-text details, IP addresses, personal content, and unknown keys are dropped.
- `screen_share_ready` and `screen_share_stopped`: empty sanitized payloads to update the technician's view.
- Every frame is limited to 32 KB by default. Unknown message types are rejected.

Technician WebSocket join attempts are rate-limited to 12 connections per minute per peer address. SDP and ICE signaling is limited to 180 messages per minute per session participant by default. A rejected code response does not reveal whether a session exists.
