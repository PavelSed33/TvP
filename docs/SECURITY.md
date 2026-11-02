# Security notes

## Implemented controls

- Six-digit codes use the operating system's cryptographic random source, expire after five minutes by default, and can be claimed once.
- A separate random client bearer token authenticates the client WebSocket. Token comparison is constant-time; the token is not placed in a URL or logged.
- The server waits for explicit client consent before relaying SDP, ICE, screen-share status, or diagnostics. Client and technician can end the session; socket loss and timeout also close it.
- Code attempts and session creation have process-local per-address limits. Message size and forwarded payload schemas are bounded.
- Diagnostic forwarding uses allowlists. Security logs contain event names and a shortened opaque session identifier only; they exclude codes, tokens, SDP, ICE addresses, and diagnostics.
- There is no hidden capture flow. Android requires its system MediaProjection prompt for each share, then keeps a typed foreground service and persistent notification visible with an in-app and notification stop action.
- The Android client uses HTTPS/WSS only and keeps the client token in memory for the active session. The technician browser receives video only; remote input is not implemented.
- TURN REST credentials use a server-side shared secret and expire no later than the session. The shared secret is not returned to either peer.
- WebRTC media uses DTLS-SRTP. The server relays signaling but does not receive or record screen video.

## Deployment and verification limits

This is not yet a production identity system. The technician has no account authentication; possession of a valid code can request a connection, which the client still must approve. Do not expose the service publicly until technician identity, abuse monitoring, operational key management, and deployment review are complete.

The in-memory session store and limiter require exactly one process and lose state on restart. Behind a reverse proxy, trust forwarded client addresses only from that proxy. The sample Nginx configuration requires real certificates and hostnames.

TURN requires coturn configured with `use-auth-secret`, a strong secret, valid public addresses, firewall rules, and working UDP/TCP/TLS listeners. Do not expose an unauthenticated public relay. Direct STUN connectivity may fail on restrictive NATs, mobile carriers, or firewalls.

The Android debug APK has been compiled and its signature verified, but the screen stream has not been validated on a real device. Stage 3 is not complete until the technician browser visibly receives Android screen pixels and stop/revocation flows pass on separate networks.
