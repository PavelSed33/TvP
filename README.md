# RemoteFix

RemoteFix is a consent-based remote-support project. Signaling, the Android client, and the Stage 3 WebRTC source are implemented. A debug APK now builds successfully, but real Android-to-browser video has not been verified. The project must not be treated as a working screen-viewing product until video is visible on a real device.

## Current capabilities

- The server creates a one-time six-digit code and a separate bearer token for the Android client. Codes expire and can be claimed once.
- The technician panel requests client consent before any WebRTC signaling or diagnostics are relayed.
- The panel creates a browser `RTCPeerConnection`, exchanges SDP and trickled ICE, displays the remote video track, and can show it full screen.
- The Android app handles an explicit MediaProjection system prompt, starts a visible `mediaProjection` foreground service, captures a downscaled screen, and publishes a video-only WebRTC track.
- The client can stop screen sharing separately from ending the support session. A persistent Android notification provides another stop action.
- The server returns STUN configuration and, when configured, short-lived TURN REST credentials to both peers. The long-lived TURN secret stays on the server.
- The Android app displays the connection code, requests support consent, keeps a visible session notification, collects limited diagnostics, and provides emergency disconnect.

Remote input/control, technician accounts, and Android TV packaging are not implemented. The browser makes one ICE restart attempt after a failed connection; recovery across repeated network changes still needs device validation. `assembleDebug` succeeds with JDK 17, Gradle 8.13, and Android SDK Platform 36. Real-device media validation remains outstanding; ADB enumeration is blocked in this sandbox by its inability to write Android user settings.

## Run the server locally

Use Python 3.10 or newer (the Docker image uses Python 3.12). From the repository root:

```sh
python3 -m venv .venv
. .venv/bin/activate
python -m pip install -r server/requirements-dev.txt
uvicorn app.main:app --app-dir server --reload --ws-max-size 32768
```

Open <http://127.0.0.1:8000> for local panel work. The Android app requires an HTTPS server origin; use a TLS development tunnel or deployed server for end-to-end Android connections. Production requires HTTPS/WSS through a TLS reverse proxy.

## Android and technician platforms

See [android-client/README.md](android-client/README.md). Build requirements: JDK 17, Android SDK Platform 36, Android Studio, and an Android 8.0+ device. The technician panel is a web app and can run in Safari, Chrome, or another supported WebRTC browser on macOS and Windows. There is no native macOS technician app.

## Deployment

```sh
docker compose -f deployment/docker-compose.yml up --build
```

The API is bound to `127.0.0.1:8000`; put the Nginx TLS configuration in `deployment/nginx/remotefix.conf` in front of it and replace the sample hostname/certificate paths. Run one Uvicorn worker: sessions and rate limits are process-local in this first stage.

For TURN relay support, configure `REMOTEFIX_TURN_URLS` and a private `REMOTEFIX_TURN_SHARED_SECRET` on the API service, then set the same secret in coturn. See [deployment/coturn/README.md](deployment/coturn/README.md). Do not expose an unauthenticated TURN relay.

## Checks

```sh
python -m unittest discover -s server/tests -v
python -m compileall -q server/app
```

Android build and device checks are described in [docs/TESTING.md](docs/TESTING.md). See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), [docs/API.md](docs/API.md), [docs/SECURITY.md](docs/SECURITY.md), and [docs/INSTALLATION.md](docs/INSTALLATION.md).
