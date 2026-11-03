# RemoteFix TV clients

This directory contains a shared TV-friendly RemoteFix client and package manifests for Samsung Tizen and LG webOS. The clients create a support code, require an on-TV consent action, and send allowlisted device diagnostics after approval. If the TV runtime exposes both WebRTC and a user-approved display-capture API, the client can publish that stream to the existing technician panel.

## Important platform limits

- Samsung documents WebRTC support by TV web-engine version; support is not uniform across model years. The app detects `RTCPeerConnection` at runtime and disables video when it is missing. Samsung's web-engine matrix lists WebRTC support only for newer engines, with some versions marked partial: [Samsung Web Engine Specifications](https://developer.samsung.com/smarttv/develop/specifications/web-engine-specifications.html).
- LG's developer forum states that WebRTC is available only to contracted partner apps. A normal developer-mode package can launch and provide the session/diagnostic flow, but video remains unavailable without LG partner authorization: [LG webOS TV developer forum](https://forum.webostv.developer.lge.com/t/inquiry-regarding-webrtc-support-on-webos-tv/28059).
- The web app only starts screen sharing when `navigator.mediaDevices.getDisplayMedia` is present and the user starts it. A TV firmware that does not expose a screen-capture API cannot stream the TV's settings, HDMI input, or other apps. No remote-control API is implemented.

## Assemble app directories

From the repository root:

```sh
python3 tv-clients/build.py
```

The script creates `tv-clients/dist/tizen` and `tv-clients/dist/webos`, copies the shared client, writes platform identifiers, and generates the webOS-required PNG icons.

## Samsung Tizen

1. Install Samsung's Tizen TV SDK and create a signing certificate profile. Samsung requires signed packages for installation.
2. Run the assembly script above.
3. Package the generated directory with Tizen Studio's `tizen` CLI, using the profile created for your TV:

   ```sh
   tizen package -t wgt -s <certificate-profile> -- tv-clients/dist/tizen
   ```

4. Install to a registered TV/emulator with `tizen install`, then verify the exact model and firmware. Minimum manifest version is Tizen 5.5; video remains capability-gated.

## LG webOS

1. Install the LG webOS TV CLI and configure a TV/emulator with Developer Mode.
2. Run the assembly script above. The generated `appinfo.json` and 80×80/130×130 PNG icons follow LG's package requirements: [webOS appinfo.json reference](https://webostv.developer.lge.com/develop/references/appinfo-json).
3. Package the app:

   ```sh
   ares-package tv-clients/dist/webos
   ```

4. Install it with `ares-install` and launch with `ares-launch`. The public runtime is expected to report WebRTC unavailable. Apply for LG's partner program before planning a video-enabled release.

The server must be reachable at a valid HTTPS origin. TV apps require a functioning WSS route as well. Do not put TURN's long-lived shared secret in either TV package.

## Verification status

The shared client logic has Node-based tests. This workspace has no Tizen CLI, webOS TV CLI, emulator, Samsung TV, or LG TV attached. Package signing, installation, remote-key behavior, firmware-specific capture APIs, and real TV-to-Mac video still require testing with the corresponding SDKs and devices.
