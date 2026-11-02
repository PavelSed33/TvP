# Architecture

## Current implementation: signaling and WebRTC source, device verification pending

```text
Android MediaProjection ── WebRTC DTLS-SRTP ── Technician browser
          │                                         │
          └────── HTTPS/WSS signaling ─ FastAPI ────┘
                                      └── short-lived TURN REST credentials
```

`server/app/main.py` hosts the REST API, both WebSocket roles, the technician panel, session expiry, and security event logging. `sessions.py` owns transient session state. `security.py` creates codes/tokens and applies process-local rate limits. `diagnostics.py` forwards selected technical fields and checks SDP/ICE payloads. `signaling.py` enforces role-specific message allowlists.

The Android `ScreenShareService` owns the one-use MediaProjection grant, Android foreground-service notification, WebRTC factory/peer connection, screen video track, and SDP/ICE processing. The ViewModel bridges signaling messages into that service. The technician panel creates a `recvonly` video peer connection, trickles ICE, and renders the remote track in a video element with full-screen support. The server forwards signaling only after client consent; Android also requires its separate MediaProjection system prompt before reporting the capture service ready.

The server keeps sessions in memory. Run one worker and one server instance; restart loses all sessions. PostgreSQL/Redis, multiple workers, and technician identity management are future work. STUN is configured by default; TURN requires an operator-deployed coturn and server-only shared secret. TURN credentials are HMAC-SHA1 REST credentials with an expiry bounded by the support-session expiry.

## Staged implementation

1. **Infrastructure and panel:** one-time codes, consent gate, signaling transport, and deployment skeleton implemented.
2. **Android client:** Kotlin UI, code/token flow, notification, diagnostics, consent prompt, and client signaling implemented.
3. **WebRTC source:** Android screen capture, peer connection, SDP/ICE relay, browser video renderer, STUN, optional TURN credential issuance, and one browser ICE restart attempt implemented. The debug APK builds successfully. **Not complete:** real Android-to-browser video verification. No later stage should be treated as started until that stream is demonstrated.
4. Diagnostics and safe fix tools: initial read-only diagnostics exist; deeper diagnostics and repair flows are pending.
5. Opt-in AccessibilityService controls: pending and subject to Android restrictions.
6. Android TV UI and device-specific support: pending.

## Session and media lifecycle

- `POST /api/sessions` creates the one-time code, client bearer token, and session ICE configuration.
- The Android client authenticates to `/ws/client/{session_id}` with that token in its first WebSocket frame.
- The technician submits the one-time code to `/ws/technician`; the server claims it once and notifies Android.
- Android waits for the owner to approve or deny the connection. No SDP, ICE, or diagnostics are relayed before approval.
- After approval, the owner separately starts screen sharing with Android's MediaProjection prompt. The app then launches its typed foreground service and sends `screen_share_ready`.
- Only then does the technician browser create a video-only receive offer. The Android service answers and exchanges trickled ICE; the browser renders the received track.
- The client can stop just screen sharing from the app or notification, or end the entire session. Either peer disconnecting, socket loss, or timeout ends the session.

All signaling depends on HTTPS/WSS. WebRTC media encryption is provided by DTLS-SRTP. Public-network behavior still needs real-device checks with TURN configured and separate networks.
