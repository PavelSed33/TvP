# Installation

## Local development

1. Install Python 3.10+ and create a virtual environment.
2. From the repository root run `python -m pip install -r server/requirements-dev.txt`.
3. Run `uvicorn app.main:app --app-dir server --reload`.
4. Open <http://127.0.0.1:8000> and check `/api/health`.
5. Use `POST /api/sessions` to create a code. For a complete consent flow, configure the Android client with the server's HTTPS origin.

## Docker

Run `docker compose -f deployment/docker-compose.yml up --build`. The service binds to localhost port 8000. The sample compose is for a local server process; configure the Nginx TLS reverse proxy before remote use.

## HTTPS/WSS reverse proxy

Install the sample file in `deployment/nginx/remotefix.conf`, replace `remotefix.example.com`, and provision certificates at the configured paths. Confirm WebSocket upgrade headers are preserved. The Compose command enables Uvicorn proxy headers and trusts only `127.0.0.1` by default. Set `UVICORN_FORWARDED_ALLOW_IPS` to the exact IP address or comma-separated IP addresses that Uvicorn sees for your reverse proxy, for example in the environment before starting Compose. Do not use `*` on an internet-facing deployment. This lets rate limits use the real client address without trusting forwarded headers from arbitrary peers. Keep Uvicorn reachable only through the reverse proxy or a private network.

## Android/Smart TV

For Android, open `android-client/` in Android Studio, install JDK 17 and Android SDK Platform 36, configure the HTTPS server origin in the app, then run `./gradlew assembleDebug` or use Android Studio's Run action. Android 13+ users must allow notifications before starting a visible session. Screen transmission requires support-session consent and a separate Android MediaProjection system approval. The debug APK has been built in this workspace; end-to-end video still needs real-device validation.

Android TV installation and interaction notes will be added with the separate TV interface. No Android TV project exists yet.
