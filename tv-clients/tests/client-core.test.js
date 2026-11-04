const assert = require("node:assert/strict");
const createRemoteFixClient = require("../common/client-core.js");

class MockWebSocket {
  constructor(url) {
    this.url = url;
    this.readyState = 0;
    this.sent = [];
    MockWebSocket.last = this;
  }
  send(raw) {
    this.sent.push(JSON.parse(raw));
    return true;
  }
  open() {
    this.readyState = 1;
    this.onopen();
  }
  receive(message) {
    this.onmessage({ data: JSON.stringify(message) });
  }
  close() {
    this.readyState = 3;
    if (this.onclose) this.onclose();
  }
}

class MockPeerConnection {
  constructor(config) {
    this.config = config;
    this.addedTracks = [];
    this.addedCandidates = [];
    this.closed = false;
    MockPeerConnection.last = this;
  }
  addTrack(track, stream) { this.addedTracks.push({ track, stream }); }
  setRemoteDescription(value) { this.remoteDescription = value; return Promise.resolve(); }
  createAnswer() { return Promise.resolve({ type: "answer", sdp: "v=0\r\nanswer" }); }
  setLocalDescription(value) { this.localDescription = value; return Promise.resolve(); }
  addIceCandidate(value) { this.addedCandidates.push(value); return Promise.resolve(); }
  close() { this.closed = true; }
}

function makeEnvironment(options = {}) {
  const tracks = [{ stopped: false, stop() { this.stopped = true; } }];
  const stream = { getTracks() { return tracks; } };
  const mediaDevices = options.capture === false ? {} : {
    getDisplayMedia() {
      options.captureCalls = (options.captureCalls || 0) + 1;
      return Promise.resolve(stream);
    }
  };
  return {
    URL,
    WebSocket: MockWebSocket,
    RTCPeerConnection: options.webRtc === false ? undefined : MockPeerConnection,
    navigator: { userAgent: "Tizen/8.0 Test TV", onLine: true, mediaDevices },
    localStorage: { getItem() { return ""; }, setItem() {} },
    fetch() {
      return Promise.resolve({
        ok: true,
        json() {
          return Promise.resolve({
            session_id: "opaque-session",
            code: "123456",
            client_token: "opaque-token",
            expires_at: "2030-01-01T00:30:00+00:00",
            ice_servers: [{ urls: ["stun:stun.example.test:3478"] }]
          });
        }
      });
    },
    _tracks: tracks,
    _stream: stream,
    _options: options
  };
}

async function testSessionConsentAndMediaSignaling() {
  const env = makeEnvironment();
  const states = [];
  const client = createRemoteFixClient({ env, platform: "Samsung Tizen TV", onChange: state => states.push(state) });
  client.saveServerUrl("https://support.example.test/");
  await client.createSession();
  assert.equal(client.getState().code, "123456");
  assert.equal(MockWebSocket.last.url, "wss://support.example.test/ws/client/opaque-session");

  const socket = MockWebSocket.last;
  socket.open();
  assert.deepEqual(socket.sent[0], { type: "authenticate", token: "opaque-token" });
  socket.receive({ type: "authenticated" });
  socket.receive({ type: "connection_request", expires_at: "2030-01-01T00:30:00+00:00" });
  assert.equal(client.getState().status, "awaiting_consent");
  assert.equal(client.getState().connectionRequest, true);

  await assert.rejects(client.startScreenShare(), /Сначала разрешите сеанс/);
  assert.equal(env._options.captureCalls || 0, 0);
  assert.equal(client.respondToRequest(true), true);
  assert.deepEqual(
    socket.sent.find(message => message.type === "consent"),
    { type: "consent", approved: true }
  );
  socket.receive({ type: "consent_result", approved: true });
  assert.equal(client.getState().status, "active");
  assert.ok(socket.sent.some(message => message.type === "device_info"));
  assert.ok(socket.sent.some(message => message.type === "diagnostics"));

  await client.startScreenShare();
  assert.ok(socket.sent.some(message => message.type === "screen_share_ready"));
  const peer = MockPeerConnection.last;
  assert.equal(peer.addedTracks.length, 1);
  socket.receive({ type: "offer", payload: { sdp: "v=0\r\noffer" } });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(peer.remoteDescription.type, "offer");
  assert.ok(socket.sent.some(message => message.type === "answer"));

  let finishRestart;
  peer.setRemoteDescription = value => new Promise(resolve => {
    finishRestart = () => {
      peer.remoteDescription = value;
      resolve();
    };
  });
  socket.receive({ type: "offer", payload: { sdp: "v=0\r\nrestart-offer" } });
  socket.receive({
    type: "ice_candidate",
    payload: { candidate: "candidate:restart", sdp_mid: "0", sdp_mline_index: 0 }
  });
  assert.equal(client.remoteDescriptionReady, false);
  assert.equal(peer.addedCandidates.length, 0);
  finishRestart();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(peer.addedCandidates.length, 1);
  assert.equal(peer.addedCandidates[0].candidate, "candidate:restart");

  peer.onicecandidate({ candidate: { candidate: "candidate:1", sdpMid: "0", sdpMLineIndex: 0 } });
  assert.ok(socket.sent.some(message => message.type === "ice_candidate"));
  client.stopScreenShare(true);
  assert.ok(peer.closed);
  assert.equal(env._tracks[0].stopped, true);
  assert.ok(socket.sent.some(message => message.type === "screen_share_stopped"));
  client.disconnect();
  assert.equal(client.getState().code, null);
  assert.equal(client.getState().status, "ended");
  assert.ok(states.length > 5);
}

async function testSocketConstructionFailureReleasesCreatedSession() {
  const env = makeEnvironment();
  env.WebSocket = function () { throw new Error("WebSocket is blocked by this firmware."); };
  const client = createRemoteFixClient({ env, platform: "Samsung Tizen TV" });
  client.saveServerUrl("https://support.example.test");
  await assert.rejects(client.createSession(), /blocked by this firmware/);
  assert.equal(client.sessionId, null);
  assert.equal(client.clientToken, null);
  assert.equal(client.getState().status, "error");
  assert.match(client.getState().error, /blocked by this firmware/);
}

function testCapabilitiesAndHttpsValidation() {
  const client = createRemoteFixClient({ env: makeEnvironment({ webRtc: false, capture: false }), platform: "LG webOS TV" });
  assert.deepEqual(client.getCapabilities(), { webRtc: false, screenCapture: false });
  assert.equal(client.normalizeServerUrl("https://support.example.test/"), "https://support.example.test");
  assert.throws(() => client.normalizeServerUrl("http://support.example.test"), /HTTPS/);
  assert.throws(() => client.normalizeServerUrl("https://support.example.test/path"), /без пути/);
}

async function main() {
  testCapabilitiesAndHttpsValidation();
  await testSessionConsentAndMediaSignaling();
  await testSocketConstructionFailureReleasesCreatedSession();
  console.log("TV_CLIENT_CORE_OK: capabilities, HTTPS validation, consent gate, diagnostics, media signaling, ICE, stop, disconnect");
}

main().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
