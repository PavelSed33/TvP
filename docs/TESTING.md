# Testing and verification status

## Server and panel

Install `server/requirements-dev.txt`, then run the checks from the repository root:

```sh
python -m unittest discover -s server/tests -v
python -m compileall -q server/app
```

Start the API and check `GET /api/health`, `POST /api/sessions`, and the technician panel at `/`. The session API and panel can run locally for interface work. Android requires an HTTPS endpoint because the app rejects cleartext HTTP.

## Android build

```sh
cd android-client
./gradlew assembleDebug
```

Install on an Android 8.0+ device. Check code creation, WebSocket auth, consent/deny, Android's screen-sharing system prompt, visible foreground notifications, SDP/ICE exchange, stopping the share from both app and notification, emergency disconnect, and ending the session remotely. On Android 14+, verify a new system permission prompt appears for each new capture session.

## Required end-to-end WebRTC validation

1. Deploy the API behind valid HTTPS/WSS and configure coturn with the same shared secret as the API.
2. Use an Android device and a macOS or Windows computer on separate networks. A localhost-only run is not a meaningful NAT/TURN test.
3. Create a session on Android and enter its code in the browser panel.
4. Approve the support request on Android, then separately approve Android's screen-capture prompt.
5. Confirm the Android notification shows sharing and the browser panel displays live screen pixels.
6. Stop sharing from Android and confirm the browser stops rendering. Repeat using the notification Stop action.
7. End the session from each peer. Repeat with mobile data, Wi-Fi, network interruption, and permission revocation.

The current workspace has an isolated JDK 17, Gradle 8.13 wrapper, Android SDK Platform 36, Build Tools 36.0.0, and adb. `./gradlew :app:assembleDebug` succeeds, and the APK signature and archive contents verify. ADB device enumeration is blocked in this sandbox because ADB cannot create its user settings under `~/.android`; no device-side checks have been run. The Android-to-browser video path remains unverified. Server tests do not prove media transport. The WebRTC implementation remains in Stage 3 until the real stream is visibly confirmed.

The server suite covers code/token flow, expiry, rate limits, diagnostic allowlisting, consent gating, status/SDP relaying, and short-lived TURN credential signing. It does not substitute for TLS, Android lifecycle, codec, TURN reachability, or cross-network checks.
