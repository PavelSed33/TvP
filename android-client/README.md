# RemoteFix Android client (Stages 2–3 implementation)

The Android app currently implements:

- One-time code creation, authenticated client WebSocket, explicit technician-request approval, and emergency disconnect.
- Limited device diagnostics and an ongoing notification for the support session.
- A separate Android MediaProjection system prompt after support-session consent.
- A `mediaProjection` foreground service that captures the display and publishes video with WebRTC after the owner approves the system prompt.
- SDP offer/answer and trickled ICE exchange with the technician browser.
- A separate stop-sharing action in the app and the ongoing notification.

The MediaProjection permission result is passed once to the capture service. Android 14+ requires fresh consent for each projection, a correctly typed foreground service, and a projection token that is used for a single capture session. Remote control and AccessibilityService are not implemented.

## Build

Requirements: Android Studio with JDK 17, Android SDK Platform 36 and Build Tools 36.x, and an Android 8.0+ device or emulator.

```sh
./gradlew assembleDebug
```

Set the deployed HTTPS server origin in the app. Cleartext HTTP is disabled. WebRTC dependency: `io.github.webrtc-sdk:android:150.7871.01`, published through Maven Central by the WebRTC SDK project.

The technician is a browser client and can run on macOS or Windows. No native macOS app is included.

## TURN

The server includes a public STUN default. To enable TURN, configure these variables on the API service:

```sh
REMOTEFIX_TURN_URLS=turn:turn.example.com:3478?transport=udp,turns:turn.example.com:5349?transport=tcp
REMOTEFIX_TURN_SHARED_SECRET=<at-least-32-random-characters>
REMOTEFIX_TURN_CREDENTIAL_TTL_SECONDS=3600
```

Set the same secret in coturn with `use-auth-secret`. The API returns time-limited TURN credentials to both peers; do not place the shared secret in this APK or browser. See [deployment/coturn/README.md](../deployment/coturn/README.md).

## Current verification status

The debug APK builds successfully with JDK 17, Gradle 8.13, Android SDK Platform 36, and Build Tools 36.0.0. A copy is available at `../outputs/RemoteFix-debug.apk`. No real device-to-browser stream has been demonstrated. Do not treat screen sharing as verified until video is visible in the technician panel over a real network.

Platform notes: [Android MediaProjection](https://developer.android.com/media/grow/media-projection), [foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types), [WebRTC Android SDK](https://github.com/webrtc-sdk/android), and [Android SDK setup](https://developer.android.com/about/versions/16/setup-sdk).
