import assert from "node:assert/strict";
import test from "node:test";

import { shouldQueueRemoteIceCandidate } from "../src/signaling.js";

test("ICE candidates wait for the initial remote description", () => {
  const peer = { remoteDescription: null, signalingState: "have-local-offer" };
  assert.equal(shouldQueueRemoteIceCandidate(peer, false), true);
});

test("ICE restart candidates wait for the new answer", () => {
  const peer = { remoteDescription: { type: "answer" }, signalingState: "have-local-offer" };
  assert.equal(shouldQueueRemoteIceCandidate(peer, false), true);
});

test("ICE candidates wait while a new answer is being applied", () => {
  const peer = { remoteDescription: { type: "answer" }, signalingState: "stable" };
  assert.equal(shouldQueueRemoteIceCandidate(peer, true), true);
});

test("ICE candidates apply immediately in a stable negotiated session", () => {
  const peer = { remoteDescription: { type: "answer" }, signalingState: "stable" };
  assert.equal(shouldQueueRemoteIceCandidate(peer, false), false);
});
