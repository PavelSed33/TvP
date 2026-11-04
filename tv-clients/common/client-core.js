(function (root, factory) {
  if (typeof module === "object" && module.exports) module.exports = factory;
  else root.RemoteFixClient = factory;
})(this, function createRemoteFixClient(options) {
  "use strict";

  options = options || {};
  var env = options.env || (typeof window !== "undefined" ? window : {});
  var onChange = typeof options.onChange === "function" ? options.onChange : function () {};
  var platform = options.platform || env.REMOTEFIX_PLATFORM || "Smart TV";
  var storageKey = "remotefix.server_origin";
  var persistedOrigin = "";

  try {
    persistedOrigin = env.localStorage ? env.localStorage.getItem(storageKey) || "" : "";
  } catch (_) {
    persistedOrigin = "";
  }

  var client = {
    platform: platform,
    serverUrl: persistedOrigin,
    sessionId: null,
    clientToken: null,
    socket: null,
    peerConnection: null,
    localStream: null,
    iceServers: [],
    remoteDescriptionReady: false,
    pendingIce: [],
    intentionallyClosing: false,
    state: {
      status: "idle",
      detail: "Создайте одноразовый код для подключения специалиста.",
      code: null,
      expiresAt: null,
      connectionRequest: false,
      screenState: "stopped",
      error: ""
    }
  };

  function copyState() {
    var copy = {};
    Object.keys(client.state).forEach(function (key) { copy[key] = client.state[key]; });
    copy.serverUrl = client.serverUrl;
    copy.platform = client.platform;
    copy.capabilities = getCapabilities();
    return copy;
  }

  function update(patch) {
    Object.keys(patch).forEach(function (key) { client.state[key] = patch[key]; });
    onChange(copyState());
  }

  function normalizeServerUrl(value) {
    if (!env.URL) throw new Error("Браузер телевизора не поддерживает проверку адреса сервера.");
    var parsed;
    try { parsed = new env.URL(String(value || "").trim()); }
    catch (_) { throw new Error("Введите корректный HTTPS-адрес сервера."); }
    if (parsed.protocol !== "https:" || !parsed.hostname || parsed.username || parsed.password ||
        (parsed.pathname && parsed.pathname !== "/") || parsed.search || parsed.hash) {
      throw new Error("Введите HTTPS-адрес сервера без пути, логина и параметров.");
    }
    return parsed.origin.replace(/\/$/, "");
  }

  function getCapabilities() {
    var mediaDevices = env.navigator && env.navigator.mediaDevices;
    return {
      webRtc: typeof env.RTCPeerConnection === "function",
      screenCapture: !!mediaDevices && typeof mediaDevices.getDisplayMedia === "function"
    };
  }

  function saveServerUrl(value) {
    var origin = normalizeServerUrl(value);
    client.serverUrl = origin;
    try {
      if (env.localStorage) env.localStorage.setItem(storageKey, origin);
    } catch (_) {
      // The URL remains available for the current app run even if TV storage is disabled.
    }
    update({ error: "", detail: "Адрес сервера сохранён на телевизоре." });
    return origin;
  }

  function send(type, payload) {
    if (!client.socket || client.socket.readyState !== 1) return false;
    var message = { type: type };
    if (payload !== undefined) message.payload = payload;
    return client.socket.send(JSON.stringify(message)) !== false;
  }

  function createSession() {
    if (!client.serverUrl) return Promise.reject(new Error("Сначала сохраните HTTPS-адрес сервера."));
    if (client.sessionId) return Promise.reject(new Error("Сначала завершите текущий сеанс."));
    if (!env.fetch || !env.WebSocket) return Promise.reject(new Error("Телевизор не поддерживает HTTPS или WebSocket."));

    client.intentionallyClosing = false;
    update({ status: "creating", detail: "Создаём одноразовый код…", code: null, error: "" });
    return env.fetch(client.serverUrl + "/api/sessions", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: "{}"
    }).then(function (response) {
      if (!response.ok) {
        return response.json().then(function (body) {
          throw new Error(body && body.detail ? body.detail : "Сервер не создал сеанс.");
        }, function () { throw new Error("Сервер не создал сеанс."); });
      }
      return response.json();
    }).then(function (data) {
      if (!data || typeof data.session_id !== "string" || typeof data.client_token !== "string" ||
          !/^[0-9]{6}$/.test(data.code || "")) throw new Error("Сервер вернул некорректные данные сеанса.");
      client.sessionId = data.session_id;
      client.clientToken = data.client_token;
      client.iceServers = Array.isArray(data.ice_servers) ? data.ice_servers : [];
      update({
        status: "connecting",
        detail: "Код создан. Подключаемся к серверу…",
        code: data.code,
        expiresAt: data.expires_at || null,
        error: ""
      });
      openSocket();
      return data;
    }).catch(function (error) {
      var message = error.message || "Ошибка сети.";
      if (client.sessionId) {
        finish("Не удалось подключить телевизор к серверу.", "error");
        update({ error: message });
      } else {
        update({ status: "error", detail: "Не удалось создать сеанс.", error: message });
      }
      throw error;
    });
  }

  function openSocket() {
    var socketUrl = client.serverUrl.replace(/^https:/i, "wss:") + "/ws/client/" + encodeURIComponent(client.sessionId);
    var socket = new env.WebSocket(socketUrl);
    client.socket = socket;
    socket.onopen = function () {
      if (client.socket !== socket) return;
      socket.send(JSON.stringify({ type: "authenticate", token: client.clientToken }));
    };
    socket.onmessage = function (event) {
      if (client.socket !== socket) return;
      var message;
      try { message = JSON.parse(event.data); }
      catch (_) { update({ error: "Сервер прислал некорректное сообщение." }); return; }
      handleMessage(message);
    };
    socket.onerror = function () {
      if (client.socket === socket) update({ status: "error", detail: "Ошибка связи с сервером.", error: "Проверьте сеть и адрес сервера." });
    };
    socket.onclose = function () {
      if (client.socket !== socket || client.intentionallyClosing) return;
      finish("Соединение с сервером завершено.", "ended");
    };
  }

  function handleMessage(message) {
    switch (message.type) {
      case "authenticated":
        update({ status: "waiting", detail: "Ожидаем, пока специалист введёт код.", error: "" });
        break;
      case "connection_request":
        update({
          status: "awaiting_consent",
          detail: "Проверьте запрос на подключение.",
          connectionRequest: true,
          expiresAt: message.expires_at || client.state.expiresAt,
          error: ""
        });
        break;
      case "consent_result":
        if (message.approved) {
          update({ status: "active", connectionRequest: false, detail: "Сеанс разрешён. Диагностика отправлена.", error: "" });
          sendDiagnostics();
        } else {
          finish("Запрос отклонён.", "ended");
        }
        break;
      case "offer":
        applyOffer(message.payload || {});
        break;
      case "ice_candidate":
        receiveIceCandidate(message.payload || {});
        break;
      case "screen_share_stopped":
        stopScreenShare(false);
        update({ detail: "Показ экрана остановлен.", screenState: "stopped" });
        break;
      case "session_ended":
        finish(message.reason || "Сеанс завершён.", "ended");
        break;
      case "error":
        update({ error: message.message || "Сервер отклонил запрос." });
        break;
      default:
        break;
    }
  }

  function respondToRequest(approved) {
    if (!client.state.connectionRequest) return false;
    var sent = false;
    if (client.socket && client.socket.readyState === 1) {
      sent = client.socket.send(JSON.stringify({ type: "consent", approved: !!approved })) !== false;
    }
    if (sent) update({ connectionRequest: false, detail: approved ? "Ожидаем подтверждение сеанса." : "Отклоняем запрос…" });
    else update({ error: "Нет связи с сервером. Повторите попытку." });
    return sent;
  }

  function sendDiagnostics() {
    if (client.state.status !== "active") return false;
    var capabilities = getCapabilities();
    var userAgent = env.navigator && env.navigator.userAgent ? env.navigator.userAgent : "Smart TV";
    var versionMatch = userAgent.match(/(?:Tizen|webOS)[\s/]+([0-9.]+)/i);
    var platformName = platform.indexOf("Samsung") >= 0 ? "Tizen" : platform.indexOf("LG") >= 0 ? "webOS" : platform;
    var checks = [
      { name: "internet", status: env.navigator && env.navigator.onLine === false ? "warning" : "ok" },
      { name: "server_connection", status: client.socket && client.socket.readyState === 1 ? "ok" : "error" },
      { name: "screen_capture_permission", status: capabilities.screenCapture ? "unknown" : "unsupported" },
      { name: "accessibility_permission", status: "unsupported" },
      { name: "storage", status: "unknown" },
      { name: "wifi", status: "unknown" },
      { name: "foreground_service", status: "unsupported" }
    ];
    send("device_info", {
      manufacturer: platform.indexOf("Samsung") >= 0 ? "Samsung" : platform.indexOf("LG") >= 0 ? "LG" : platform,
      model: userAgent.slice(0, 100),
      platform: platformName,
      os_version: versionMatch ? versionMatch[1] : "unknown",
      app_version: "0.1.0",
      network_type: "unknown",
      app_status: client.state.screenState === "streaming" ? "streaming" : "connected"
    });
    send("diagnostics", { checks: checks });
    return true;
  }

  function startScreenShare() {
    var capabilities = getCapabilities();
    if (client.state.status !== "active") return Promise.reject(new Error("Сначала разрешите сеанс поддержки."));
    if (!capabilities.webRtc) return Promise.reject(new Error(webRtcUnavailableMessage()));
    if (!capabilities.screenCapture) return Promise.reject(new Error("Прошивка телевизора не предоставляет приложению API захвата экрана."));
    if (client.peerConnection) return Promise.reject(new Error("Показ экрана уже запущен."));

    update({ screenState: "starting", detail: "Ожидаем системное разрешение на показ экрана…", error: "" });
    return env.navigator.mediaDevices.getDisplayMedia({ video: true, audio: false }).then(function (stream) {
      if (client.state.status !== "active") {
        stream.getTracks().forEach(function (track) { track.stop(); });
        throw new Error("Сеанс завершился до начала показа экрана.");
      }
      client.localStream = stream;
      var peer = new env.RTCPeerConnection({ iceServers: client.iceServers });
      client.peerConnection = peer;
      client.remoteDescriptionReady = false;
      client.pendingIce = [];
      stream.getTracks().forEach(function (track) {
        peer.addTrack(track, stream);
        track.onended = function () { stopScreenShare(true); };
      });
      peer.onicecandidate = function (event) {
        if (!event.candidate || client.peerConnection !== peer) return;
        send("ice_candidate", {
          candidate: event.candidate.candidate,
          sdp_mid: event.candidate.sdpMid,
          sdp_mline_index: event.candidate.sdpMLineIndex
        });
      };
      peer.onconnectionstatechange = function () {
        if (client.peerConnection !== peer) return;
        var state = peer.connectionState;
        if (state === "connected") update({ screenState: "streaming", detail: "Экран передаётся специалисту.", error: "" });
        else if (state === "failed") update({ screenState: "error", error: "Видеосвязь не установлена. Проверьте TURN и сеть." });
        else if (state === "connecting") update({ screenState: "connecting", detail: "Устанавливаем защищённую видеосвязь…" });
      };
      if (!send("screen_share_ready")) {
        stopScreenShare(false);
        throw new Error("Сигнальное соединение недоступно.");
      }
      update({ screenState: "waiting", detail: "Показ разрешён. Ожидаем подключение специалиста." });
      sendDiagnostics();
      return true;
    }).catch(function (error) {
      if (client.peerConnection || client.localStream) stopScreenShare(false);
      update({ screenState: "error", error: error.message || "Не удалось начать показ экрана." });
      throw error;
    });
  }

  function applyOffer(payload) {
    var peer = client.peerConnection;
    if (!peer || !payload.sdp) {
      update({ error: "Получено предложение WebRTC без активного источника экрана." });
      return;
    }
    client.remoteDescriptionReady = false;
    peer.setRemoteDescription({ type: "offer", sdp: payload.sdp }).then(function () {
      if (client.peerConnection !== peer) return null;
      client.remoteDescriptionReady = true;
      return flushPendingIce(peer).then(function () { return peer.createAnswer(); });
    }).then(function (answer) {
      if (!answer || client.peerConnection !== peer) return;
      return peer.setLocalDescription(answer).then(function () {
        if (!send("answer", { sdp: peer.localDescription.sdp })) throw new Error("Не удалось отправить WebRTC-ответ.");
        update({ screenState: "connecting", detail: "Устанавливаем защищённую видеосвязь…" });
      });
    }).catch(function (error) {
      update({ screenState: "error", error: error.message || "Ошибка WebRTC-согласования." });
    });
  }

  function receiveIceCandidate(payload) {
    if (!payload.candidate) return;
    if (!client.peerConnection || !client.remoteDescriptionReady) {
      client.pendingIce.push(payload);
      return;
    }
    addIceCandidate(client.peerConnection, payload).catch(function (error) {
      update({ error: error.message || "Не удалось применить ICE-кандидат." });
    });
  }

  function addIceCandidate(peer, payload) {
    return peer.addIceCandidate({
      candidate: payload.candidate,
      sdpMid: payload.sdp_mid === undefined ? null : payload.sdp_mid,
      sdpMLineIndex: payload.sdp_mline_index === undefined ? null : payload.sdp_mline_index
    });
  }

  function flushPendingIce(peer) {
    var pending = client.pendingIce.slice();
    client.pendingIce = [];
    return pending.reduce(function (promise, candidate) {
      return promise.then(function () {
        if (client.peerConnection !== peer) return;
        return addIceCandidate(peer, candidate);
      });
    }, Promise.resolve());
  }

  function stopScreenShare(notifyServer) {
    if (notifyServer !== false) send("screen_share_stopped");
    var peer = client.peerConnection;
    client.peerConnection = null;
    client.remoteDescriptionReady = false;
    client.pendingIce = [];
    if (peer) {
      peer.onicecandidate = null;
      peer.onconnectionstatechange = null;
      try { peer.close(); } catch (_) {}
    }
    var stream = client.localStream;
    client.localStream = null;
    if (stream && stream.getTracks) stream.getTracks().forEach(function (track) {
      track.onended = null;
      try { track.stop(); } catch (_) {}
    });
    update({ screenState: "stopped" });
  }

  function finish(detail, status) {
    client.intentionallyClosing = true;
    stopScreenShare(false);
    var socket = client.socket;
    client.socket = null;
    client.sessionId = null;
    client.clientToken = null;
    client.iceServers = [];
    client.pendingIce = [];
    if (socket) {
      try { socket.close(); } catch (_) {}
    }
    update({
      status: status || "ended",
      detail: detail || "Сеанс завершён.",
      code: null,
      expiresAt: null,
      connectionRequest: false,
      screenState: "stopped",
      error: ""
    });
  }

  function disconnect() {
    if (client.sessionId) {
      stopScreenShare(true);
      send("disconnect");
      finish("Вы завершили сеанс.", "ended");
    } else {
      finish("Сеанс завершён.", "idle");
    }
  }

  function webRtcUnavailableMessage() {
    if (platform.indexOf("LG") >= 0) return "webOS разрешает WebRTC только контрактным приложениям LG. Диагностика остаётся доступной.";
    return "Эта версия Tizen не предоставляет приложению WebRTC. Проверьте модель и версию прошивки телевизора.";
  }

  client.getState = copyState;
  client.getCapabilities = getCapabilities;
  client.saveServerUrl = saveServerUrl;
  client.createSession = createSession;
  client.respondToRequest = respondToRequest;
  client.sendDiagnostics = sendDiagnostics;
  client.startScreenShare = startScreenShare;
  client.stopScreenShare = stopScreenShare;
  client.disconnect = disconnect;
  client.handleMessage = handleMessage;
  client.normalizeServerUrl = normalizeServerUrl;
  return client;
});
