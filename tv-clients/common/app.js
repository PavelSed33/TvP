(function () {
  "use strict";

  var platform = window.REMOTEFIX_PLATFORM || "Smart TV";
  var elements = {
    status: document.getElementById("status"),
    platform: document.getElementById("platform-name"),
    serverUrl: document.getElementById("server-url"),
    saveServer: document.getElementById("save-server"),
    create: document.getElementById("create-session"),
    disconnect: document.getElementById("disconnect"),
    codePanel: document.getElementById("code-panel"),
    code: document.getElementById("connection-code"),
    expiry: document.getElementById("expiry"),
    detail: document.getElementById("session-detail"),
    consent: document.getElementById("consent-panel"),
    approve: document.getElementById("approve"),
    deny: document.getElementById("deny"),
    error: document.getElementById("error"),
    webrtc: document.getElementById("webrtc-state"),
    capture: document.getElementById("capture-state"),
    note: document.getElementById("platform-note"),
    share: document.getElementById("share-screen"),
    diagnostics: document.getElementById("send-diagnostics"),
    diagnosticsState: document.getElementById("diagnostics-state")
  };

  var client = window.RemoteFixClient({ platform: platform, onChange: render });
  elements.platform.textContent = platform;
  elements.serverUrl.value = client.serverUrl;
  render(client.getState());

  elements.saveServer.addEventListener("click", function () {
    try {
      client.saveServerUrl(elements.serverUrl.value);
      elements.serverUrl.value = client.serverUrl;
    } catch (error) {
      showError(error.message);
    }
  });

  elements.create.addEventListener("click", function () {
    client.createSession().catch(function (error) { showError(error.message); });
  });
  elements.approve.addEventListener("click", function () { client.respondToRequest(true); });
  elements.deny.addEventListener("click", function () { client.respondToRequest(false); });
  elements.disconnect.addEventListener("click", function () { client.disconnect(); });
  elements.diagnostics.addEventListener("click", function () { client.sendDiagnostics(); });
  elements.share.addEventListener("click", function () {
    var state = client.getState();
    if (state.screenState !== "stopped" && state.screenState !== "error") {
      client.stopScreenShare(true);
    } else {
      client.startScreenShare().catch(function (error) { showError(error.message); });
    }
  });

  function showError(message) {
    elements.error.textContent = message || "Неизвестная ошибка.";
    elements.error.hidden = false;
  }

  function render(state) {
    var labels = {
      idle: ["Не подключено", "disconnected"],
      creating: ["Создание кода…", "pending"],
      connecting: ["Подключаемся…", "pending"],
      waiting: ["Ожидаем специалиста", "pending"],
      awaiting_consent: ["Нужно ваше согласие", "pending"],
      active: ["Сеанс разрешён", "active"],
      ended: ["Сеанс завершён", "disconnected"],
      error: ["Ошибка связи", "error"]
    };
    var label = labels[state.status] || [state.status, "pending"];
    elements.status.textContent = label[0];
    elements.status.setAttribute("data-state", label[1]);
    elements.detail.textContent = state.detail || "";
    elements.codePanel.hidden = !state.code;
    elements.code.textContent = state.code || "------";
    elements.expiry.textContent = state.expiresAt ? "Действует до: " + state.expiresAt : "";
    elements.consent.hidden = !state.connectionRequest;
    elements.create.disabled = !!state.code || state.status === "creating" || state.status === "connecting";
    elements.disconnect.disabled = !state.code;
    elements.diagnostics.disabled = state.status !== "active";
    elements.error.hidden = !state.error;
    elements.error.textContent = state.error || "";
    elements.diagnosticsState.textContent = state.status === "active" ? "Сеанс подключён" : "Доступна после согласия";

    var caps = state.capabilities || client.getCapabilities();
    elements.webrtc.textContent = caps.webRtc ? "Доступен" : "Нет доступа в этой прошивке";
    elements.capture.textContent = caps.screenCapture ? "API обнаружен" : "API захвата экрана недоступен";
    elements.share.disabled = state.status !== "active" || !caps.webRtc || !caps.screenCapture;
    elements.share.textContent = state.screenState === "stopped" || state.screenState === "error" ? "Разрешить показ экрана" : "Остановить показ экрана";

    if (!caps.webRtc) {
      elements.note.textContent = platform.indexOf("LG") >= 0
        ? "LG открывает WebRTC для контрактных приложений. Без партнёрского доступа доступны код сеанса и диагностика."
        : "Поддержка WebRTC зависит от модели и версии Tizen. Приложение проверит доступность при запуске.";
    } else if (!caps.screenCapture) {
      elements.note.textContent = "WebRTC найден, но прошивка не предоставляет приложению захват экрана. Сеанс и диагностика работают отдельно.";
    } else {
      elements.note.textContent = "Передача экрана появится после отдельного системного подтверждения.";
    }
  }

  document.addEventListener("keydown", function (event) {
    var key = event.key || "";
    var code = event.keyCode;
    if (key === "Back" || key === "Escape" || code === 461 || code === 27 || code === 10009) {
      event.preventDefault();
      if (client.sessionId) elements.disconnect.focus();
      else elements.serverUrl.focus();
      return;
    }
    if (["ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown"].indexOf(key) < 0 && [37, 38, 39, 40].indexOf(code) < 0) return;
    var controls = Array.prototype.slice.call(document.querySelectorAll("button:not([disabled]), input:not([disabled])"))
      .filter(function (item) { return !item.hidden && item.offsetParent !== null; });
    if (!controls.length) return;
    var current = controls.indexOf(document.activeElement);
    var delta = key === "ArrowLeft" || key === "ArrowUp" || code === 37 || code === 38 ? -1 : 1;
    controls[(current + delta + controls.length) % controls.length].focus();
    event.preventDefault();
  });

  window.addEventListener("beforeunload", function () {
    if (client.sessionId) client.disconnect();
  });
})();
