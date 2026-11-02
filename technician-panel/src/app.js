import { openTechnicianSocket, sendMessage } from "./signaling.js";

const form = document.querySelector("#connect-form");
const codeInput = document.querySelector("#connection-code");
const connectButton = document.querySelector("#connect-button");
const disconnectButton = document.querySelector("#disconnect-button");
const fullscreenButton = document.querySelector("#fullscreen-button");
const statusBadge = document.querySelector("#connection-status");
const eventLog = document.querySelector("#event-log");
const screenMessage = document.querySelector("#screen-message");
const screenDetail = document.querySelector("#screen-detail");
const screenPlaceholder = document.querySelector(".screen-placeholder");
const remoteScreen = document.querySelector("#remote-screen");
let socket = null;
let peerConnection = null;
let connected = false;
let consentApproved = false;
let iceServers = [];
let pendingIceCandidates = [];
let remoteDescriptionPending = false;
let iceRestartAttempts = 0;
const MAX_PENDING_ICE_CANDIDATES = 128;

form.addEventListener("submit", (event) => {
  event.preventDefault();
  const code = codeInput.value.trim();
  if (!/^\d{6}$/.test(code)) {
    setStatus("Введите шестизначный код", "error");
    codeInput.focus();
    return;
  }
  consentApproved = false;
  cleanupPeerConnection();
  if (socket && socket.readyState < WebSocket.CLOSING) socket.close();
  clearDeviceDetails();
  appendLog("Отправлен запрос на подключение по коду.");
  setStatus("Соединение с сервером…", "connecting");
  connectButton.disabled = true;
  socket = openTechnicianSocket(code, {
    onMessage: handleMessage,
    onError: (message) => appendLog(message, "error"),
    onClose: handleClose,
  });
});

disconnectButton.addEventListener("click", () => {
  if (socket && socket.readyState === WebSocket.OPEN) {
    sendMessage(socket, { type: "disconnect" });
    appendLog("Отправлена команда завершения сеанса.");
  } else {
    socket?.close();
  }
  cleanupPeerConnection();
});

fullscreenButton.addEventListener("click", async () => {
  try {
    if (document.fullscreenElement) await document.exitFullscreen();
    else await remoteScreen.requestFullscreen();
  } catch (error) {
    appendLog(`Не удалось открыть полноэкранный режим: ${error.message || "ошибка браузера"}.`, "error");
  }
});

document.querySelector("#clear-log").addEventListener("click", () => {
  eventLog.replaceChildren();
  appendLog("Журнал очищен.");
});

function handleMessage(message) {
  switch (message.type) {
    case "joined":
      connected = true;
      disconnectButton.disabled = false;
      iceServers = Array.isArray(message.ice_servers) ? message.ice_servers : [];
      setStatus(stateLabel(message.state), "pending");
      screenMessage.textContent = message.state === "awaiting_consent" ? "Ожидание согласия клиента" : "Ожидание клиента";
      screenDetail.textContent = "Клиент должен подтвердить запрос и отдельно разрешить захват экрана в Android.";
      appendLog("Сервер принял код; запрос на подтверждение отправлен клиенту.");
      break;
    case "client_connected":
      setStatus("Клиент подключён · ожидается согласие", "pending");
      appendLog("Клиент установил защищённое соединение с сервером.");
      break;
    case "connection_request":
      setStatus("Запрос отправлен клиенту", "pending");
      appendLog("Клиенту показан запрос на подключение.");
      break;
    case "consent_result":
      if (message.approved) {
        consentApproved = true;
        setStatus("Клиент подтвердил подключение", "active");
        screenMessage.textContent = "Ожидание разрешения на показ экрана";
        screenDetail.textContent = "На Android нужно нажать «Разрешить показ экрана» и подтвердить системный запрос.";
        appendLog("Клиент дал согласие на сеанс.");
      } else {
        consentApproved = false;
        setStatus("Клиент отказал", "error");
        appendLog("Клиент отклонил запрос.", "error");
      }
      break;
    case "screen_share_ready":
      if (peerConnection) {
        appendLog("Повторное событие готовности экрана пропущено.");
        break;
      }
      consentApproved = true;
      screenMessage.textContent = "Настраиваем защищённое видеосоединение";
      screenDetail.textContent = "После установления соединения здесь появится изображение экрана клиента.";
      appendLog("Android подтвердил запуск разрешённого захвата экрана.");
      startPeerConnection().catch((error) => {
        setStatus("Не удалось настроить видеосвязь", "error");
        screenMessage.textContent = "Не удалось начать видеосвязь";
        screenDetail.textContent = error.message || "Проверьте настройки WebRTC и повторите подключение.";
        appendLog(`Ошибка WebRTC: ${error.message || "неизвестная ошибка"}.`, "error");
      });
      break;
    case "screen_share_stopped":
      cleanupPeerConnection();
      setStatus("Показ экрана остановлен", "pending");
      screenMessage.textContent = "Показ экрана остановлен клиентом";
      screenDetail.textContent = "Клиент может снова разрешить показ экрана в приложении Android.";
      appendLog("Клиент остановил трансляцию экрана.");
      break;
    case "answer":
      if (peerConnection && consentApproved) {
        const pc = peerConnection;
        remoteDescriptionPending = true;
        pc.setRemoteDescription({ type: "answer", sdp: message.payload?.sdp })
          .then(() => flushPendingIceCandidates(pc))
          .catch((error) => {
            if (pc === peerConnection) {
              remoteDescriptionPending = false;
              pendingIceCandidates = [];
            }
            appendLog(`Некорректный SDP-ответ: ${error.message}.`, "error");
          });
      }
      break;
    case "ice_candidate":
      receiveIceCandidate(message.payload).catch((error) => appendLog(`Ошибка ICE-кандидата: ${error.message}.`, "error"));
      break;
    case "device_info":
      updateDevice(message.payload || {});
      appendLog("Получены сведения об устройстве.");
      break;
    case "diagnostics":
      updateDevice(message.payload || {});
      appendLog("Получен диагностический отчёт.");
      break;
    case "session_ended":
      cleanupPeerConnection();
      setStatus(endReason(message.reason), "disconnected");
      screenMessage.textContent = "Сеанс завершён";
      screenDetail.textContent = "Для нового подключения потребуется новый одноразовый код.";
      appendLog(`Сеанс завершён: ${endReason(message.reason).toLowerCase()}.`);
      break;
    case "error":
      setStatus(message.message || "Ошибка подключения", "error");
      appendLog(message.message || "Сервер отклонил запрос.", "error");
      break;
    default:
      appendLog(`Получено событие: ${message.type || "неизвестный тип"}.`);
  }
}

async function startPeerConnection() {
  cleanupPeerConnection({ resetConsent: false });
  iceRestartAttempts = 0;
  if (!window.RTCPeerConnection) throw new Error("Этот браузер не поддерживает WebRTC.");

  const pc = new RTCPeerConnection({ iceServers });
  peerConnection = pc;
  pc.addTransceiver("video", { direction: "recvonly" });
  const signalState = { offerReady: false, pendingIceCandidates: [] };
  pc.onicecandidate = ({ candidate }) => {
    if (!candidate || pc !== peerConnection) return;
    if (!signalState.offerReady) {
      if (signalState.pendingIceCandidates.length < MAX_PENDING_ICE_CANDIDATES) signalState.pendingIceCandidates.push(candidate);
      else appendLog("Слишком много локальных ICE-кандидатов; лишние кандидаты пропущены.", "error");
    }
    else sendIceCandidate(candidate);
  };
  pc.ontrack = ({ track, streams }) => {
    if (pc !== peerConnection) return;
    if (streams?.[0]) {
      remoteScreen.srcObject = streams[0];
    } else {
      const stream = remoteScreen.srcObject || new MediaStream();
      if (!stream.getTracks().includes(track)) stream.addTrack(track);
      remoteScreen.srcObject = stream;
    }
    remoteScreen.hidden = false;
    screenPlaceholder.hidden = true;
    fullscreenButton.disabled = false;
    remoteScreen.play().catch(() => appendLog("Нажмите на видео, чтобы начать воспроизведение в этом браузере."));
    appendLog("Получен видеопоток экрана Android.");
  };
  pc.onconnectionstatechange = () => {
    if (pc !== peerConnection) return;
    if (pc.connectionState === "connected") {
      iceRestartAttempts = 0;
      setStatus("Экран передаётся", "active");
      appendLog("WebRTC-соединение установлено.");
    } else if (pc.connectionState === "failed") {
      if (iceRestartAttempts < 1) {
        iceRestartAttempts += 1;
        setStatus("Восстанавливаем видеосвязь…", "pending");
        screenMessage.textContent = "Пробуем восстановить соединение";
        screenDetail.textContent = "WebRTC повторно проверяет сетевые маршруты.";
        appendLog("ICE-связь потеряна; отправляем запрос на переподключение.", "error");
        restartIceConnection(pc, signalState).catch((error) => appendLog(`Не удалось перезапустить ICE: ${error.message}.`, "error"));
      } else {
        setStatus("Не удалось установить видеосвязь", "error");
        screenMessage.textContent = "Нет доступного сетевого маршрута";
        screenDetail.textContent = "Проверьте TURN на сервере и доступность UDP/TCP-портов, затем создайте новый сеанс.";
        appendLog("Повторная ICE-проверка не установила соединение. Проверьте TURN и сетевые правила.", "error");
      }
    } else if (pc.connectionState === "disconnected") {
      setStatus("Связь временно потеряна", "pending");
      appendLog("WebRTC временно потерял сетевое соединение.", "error");
    }
  };

  await sendOffer(pc, false, signalState);
  if (pc !== peerConnection) return;
  screenMessage.textContent = "Ожидание видеопотока";
  screenDetail.textContent = "Клиент разрешил показ экрана; браузер подключается автоматически.";
  appendLog("SDP-предложение отправлено Android-клиенту.");
}

async function restartIceConnection(pc, signalState) {
  if (pc !== peerConnection) return;
  await sendOffer(pc, true, signalState);
}

async function sendOffer(pc, iceRestart, signalState) {
  signalState.offerReady = false;
  signalState.pendingIceCandidates = [];
  const offer = await pc.createOffer(iceRestart ? { iceRestart: true } : undefined);
  if (pc !== peerConnection) return;
  await pc.setLocalDescription(offer);
  if (pc !== peerConnection || !consentApproved) return;
  if (!sendMessage(socket, { type: "offer", payload: { sdp: offer.sdp } })) {
    throw new Error("Сервер сигнализации недоступен.");
  }
  signalState.offerReady = true;
  const queued = signalState.pendingIceCandidates.splice(0);
  queued.forEach(sendIceCandidate);
}

function sendIceCandidate(candidate) {
  const payload = {
    candidate: candidate.candidate,
    sdp_mid: candidate.sdpMid,
    sdp_mline_index: candidate.sdpMLineIndex,
  };
  if (!sendMessage(socket, { type: "ice_candidate", payload })) {
    appendLog("Не удалось отправить ICE-кандидат.", "error");
  }
}

async function receiveIceCandidate(payload) {
  if (!payload?.candidate) return;
  const pc = peerConnection;
  if (!pc || !consentApproved) return;
  const candidate = new RTCIceCandidate({
    candidate: payload.candidate,
    sdpMid: payload.sdp_mid ?? null,
    sdpMLineIndex: payload.sdp_mline_index ?? null,
  });
  if (remoteDescriptionPending || !pc.remoteDescription) {
    if (pendingIceCandidates.length >= MAX_PENDING_ICE_CANDIDATES) {
      throw new Error("Слишком много ожидающих ICE-кандидатов.");
    }
    pendingIceCandidates.push(candidate);
    return;
  }
  await pc.addIceCandidate(candidate);
}

async function flushPendingIceCandidates(pc) {
  if (!pc || pc !== peerConnection) return;
  const candidates = pendingIceCandidates;
  pendingIceCandidates = [];
  remoteDescriptionPending = false;
  for (const candidate of candidates) {
    if (pc !== peerConnection) return;
    await pc.addIceCandidate(candidate);
  }
}

function cleanupPeerConnection({ resetConsent = true } = {}) {
  if (resetConsent) consentApproved = false;
  pendingIceCandidates = [];
  remoteDescriptionPending = false;
  if (peerConnection) {
    peerConnection.onicecandidate = null;
    peerConnection.ontrack = null;
    peerConnection.onconnectionstatechange = null;
    peerConnection.close();
    peerConnection = null;
  }
  remoteScreen.pause();
  remoteScreen.srcObject = null;
  remoteScreen.hidden = true;
  screenPlaceholder.hidden = false;
  fullscreenButton.disabled = true;
}

function handleClose(event) {
  cleanupPeerConnection();
  if (connected) appendLog("WebSocket-соединение закрыто.");
  connected = false;
  connectButton.disabled = false;
  disconnectButton.disabled = true;
  if (statusBadge.dataset.state !== "error" && statusBadge.dataset.state !== "disconnected") {
    setStatus("Не подключено", "disconnected");
  }
  if (event.code === 1006) appendLog("Соединение оборвалось. Проверьте сеть и повторите подключение.", "error");
}

function updateDevice(data) {
  const model = [data.manufacturer, data.model].filter(Boolean).join(" ");
  setDetail("device-state", data.app_status || data.state || "Подключено");
  setDetail("device-model", model || "—");
  setDetail("device-android", data.android_version || "—");
  setDetail("device-battery", data.battery_percent == null ? "—" : `${data.battery_percent}%`);
  setDetail("device-network", data.network_type || data.network || "—");
}

function clearDeviceDetails() {
  for (const id of ["device-state", "device-model", "device-android", "device-battery", "device-network"]) {
    setDetail(id, id === "device-state" ? "Нет данных" : "—");
  }
}

function setDetail(id, value) {
  document.getElementById(id).textContent = String(value);
}

function setStatus(label, state) {
  statusBadge.textContent = label;
  statusBadge.dataset.state = state;
}

function appendLog(message, kind = "info") {
  const item = document.createElement("li");
  const time = new Intl.DateTimeFormat(undefined, { hour: "2-digit", minute: "2-digit", second: "2-digit" }).format(new Date());
  const stamp = document.createElement("time");
  stamp.textContent = time;
  const text = document.createElement("span");
  text.textContent = message;
  item.dataset.kind = kind;
  item.append(stamp, text);
  eventLog.prepend(item);
  while (eventLog.children.length > 100) eventLog.lastElementChild.remove();
}

function stateLabel(state) {
  if (state === "awaiting_consent") return "Ожидается согласие клиента";
  if (state === "awaiting_client") return "Ожидание подключения клиента";
  return "Ожидание клиента";
}

function endReason(reason) {
  const labels = {
    client_disconnected: "Клиент отключился",
    technician_disconnected: "Специалист отключился",
    client_connection_lost: "Связь с клиентом потеряна",
    technician_connection_lost: "Связь со специалистом потеряна",
    consent_denied: "Клиент отказал",
    session_expired: "Время сеанса истекло",
    server_shutdown: "Сервер остановлен",
  };
  return labels[reason] || "Сеанс завершён";
}

appendLog("Панель готова к подключению.");
