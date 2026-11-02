export function openTechnicianSocket(code, handlers = {}) {
  const scheme = window.location.protocol === "https:" ? "wss:" : "ws:";
  const socket = new WebSocket(`${scheme}//${window.location.host}/ws/technician`);

  socket.addEventListener("open", () => {
    socket.send(JSON.stringify({ type: "join", code }));
  });
  socket.addEventListener("message", (event) => {
    let message;
    try {
      message = JSON.parse(event.data);
    } catch {
      handlers.onError?.("Сервер прислал некорректное сообщение.");
      return;
    }
    handlers.onMessage?.(message);
  });
  socket.addEventListener("error", () => handlers.onError?.("Не удалось подключиться к серверу."));
  socket.addEventListener("close", (event) => handlers.onClose?.(event));
  return socket;
}

export function sendMessage(socket, message) {
  if (!socket || socket.readyState !== WebSocket.OPEN) return false;
  socket.send(JSON.stringify(message));
  return true;
}
