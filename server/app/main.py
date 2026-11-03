"""RemoteFix session API, WebSocket signaling, and static technician panel."""

import asyncio
import base64
from contextlib import asynccontextmanager, suppress
import hashlib
import hmac
import json
import logging
import re
from pathlib import Path
import time
from typing import Any, Dict, Optional

from fastapi import FastAPI, HTTPException, Request, WebSocket, WebSocketDisconnect
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles

from .config import Settings
from .diagnostics import sanitize_payload
from .security import SlidingWindowLimiter
from .sessions import RemoteSession, SessionStore
from .signaling import validate_signal


BASE_DIR = Path(__file__).resolve().parents[2]
PANEL_DIR = BASE_DIR / "technician-panel"
settings = Settings.from_env()
sessions = SessionStore(settings.code_ttl_seconds, settings.session_ttl_seconds)
creation_limiter = SlidingWindowLimiter(settings.create_limit_per_minute)
join_limiter = SlidingWindowLimiter(settings.join_limit_per_minute)
signaling_limiter = SlidingWindowLimiter(settings.signal_limit_per_minute)
RATE_LIMITED_SIGNAL_TYPES = {"offer", "answer", "ice_candidate"}
logger = logging.getLogger("remotefix.security")
logger.setLevel(logging.INFO)
if not logger.handlers:
    handler = logging.StreamHandler()
    handler.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(message)s"))
    logger.addHandler(handler)
    logger.propagate = False


@asynccontextmanager
async def lifespan(application: FastAPI):
    expiry_task = asyncio.create_task(_expiry_sweeper())
    application.state.expiry_task = expiry_task
    try:
        yield
    finally:
        expiry_task.cancel()
        with suppress(asyncio.CancelledError):
            await expiry_task
        for session_id in list(sessions.by_id):
            session = sessions.get_by_id(session_id)
            if session:
                await _terminate(session, "server_shutdown")


app = FastAPI(title="RemoteFix Signaling", version="0.1.0", lifespan=lifespan)
app.mount("/assets", StaticFiles(directory=str(PANEL_DIR / "src")), name="assets")


@app.get("/", include_in_schema=False)
async def technician_panel() -> FileResponse:
    return FileResponse(PANEL_DIR / "index.html")


@app.get("/api/health")
async def health() -> Dict[str, str]:
    return {"status": "ok", "stage": "3-webrtc-in-progress"}


@app.post("/api/sessions", status_code=201)
async def create_session(request: Request) -> Dict[str, Any]:
    if not creation_limiter.allow(_peer_key(request.client.host if request.client else "unknown")):
        raise HTTPException(status_code=429, detail="Too many sessions created from this address")
    session = sessions.create()
    _audit("session_created", session)
    return {
        "session_id": session.session_id,
        "code": session.code,
        "client_token": session.client_token,
        "expires_at": session.expires_at.isoformat(),
        "code_ttl_seconds": settings.code_ttl_seconds,
        "ice_servers": _ice_servers_for_session(session),
    }


@app.websocket("/ws/client/{session_id}")
async def client_socket(websocket: WebSocket, session_id: str) -> None:
    await websocket.accept()
    session: Optional[RemoteSession] = None
    try:
        auth = await _receive_message(websocket, settings.client_auth_timeout_seconds)
        if auth.get("type") != "authenticate" or not isinstance(auth.get("token"), str):
            await _reject(websocket, "Authentication required")
            return
        session = sessions.get_by_id(session_id)
        if session is None or not sessions.authenticate_client(session, auth["token"]):
            await _reject(websocket, "Session is invalid or expired")
            return
        if not sessions.attach_client(session, websocket):
            await _reject(websocket, "Client is already connected or session expired")
            return

        await websocket.send_json({"type": "authenticated", "state": session.state})
        _audit("client_connected", session)
        if session.technician_ws:
            await session.technician_ws.send_json({"type": "client_connected", "state": session.state})
            await websocket.send_json({
                "type": "connection_request",
                "message": "A technician is requesting access",
                "expires_at": session.expires_at.isoformat(),
            })

        while True:
            message = await _receive_message(websocket)
            error = validate_signal(message, "client", session.state == "active")
            if error:
                await websocket.send_json({"type": "error", "message": error})
                continue
            message_type = message["type"]
            if message_type in RATE_LIMITED_SIGNAL_TYPES and not signaling_limiter.allow(f"{session.session_id}:client"):
                await websocket.send_json({"type": "error", "message": "Too many WebRTC signaling messages. Try again shortly."})
                continue
            if message_type == "consent":
                if session.state != "awaiting_consent":
                    await websocket.send_json({"type": "error", "message": "There is no pending connection request"})
                    continue
                if message["approved"]:
                    sessions.set_active(session)
                    await websocket.send_json({"type": "consent_result", "approved": True})
                    if session.technician_ws:
                        await session.technician_ws.send_json({"type": "consent_result", "approved": True})
                    _audit("consent_granted", session)
                else:
                    if session.technician_ws:
                        await session.technician_ws.send_json({"type": "consent_result", "approved": False})
                    await _terminate(session, "consent_denied")
                    return
                continue
            if message_type == "disconnect":
                await _terminate(session, "client_disconnected")
                return
            await _forward(session.technician_ws, websocket, message)
    except WebSocketDisconnect:
        if session:
            await _terminate(session, "client_connection_lost")
    except asyncio.TimeoutError:
        await _reject(websocket, "Client authentication timed out")
    except (ValueError, json.JSONDecodeError) as exc:
        await _reject(websocket, str(exc))
        if session:
            await _terminate(session, "invalid_client_message")
    except Exception:
        logger.exception("Unexpected client WebSocket error")
        if session:
            await _terminate(session, "server_error")
    finally:
        if session and session.session_id in sessions.by_id:
            sessions.detach(session, websocket)


@app.websocket("/ws/technician")
async def technician_socket(websocket: WebSocket) -> None:
    await websocket.accept()
    session: Optional[RemoteSession] = None
    try:
        peer_key = _peer_key(websocket.client.host if websocket.client else "unknown")
        if not join_limiter.allow(peer_key):
            _audit("join_rate_limited", None)
            await _reject(websocket, "Too many code attempts. Try again later.", 1008)
            return
        join = await _receive_message(websocket, settings.technician_auth_timeout_seconds)
        code = join.get("code")
        if join.get("type") != "join" or not isinstance(code, str) or not re.fullmatch(r"[0-9]{6}", code):
            await _reject(websocket, "A six-digit connection code is required")
            return
        session = sessions.get_by_code(code)
        if session is None or not sessions.attach_technician(session, websocket):
            _audit("join_rejected", None)
            await _reject(websocket, "Code is invalid, expired, or already used", 1008)
            return

        await websocket.send_json({
            "type": "joined",
            "state": session.state,
            "expires_at": session.expires_at.isoformat(),
            "ice_servers": _ice_servers_for_session(session),
        })
        _audit("technician_joined", session)
        if session.client_ws:
            await session.client_ws.send_json({
                "type": "connection_request",
                "message": "A technician is requesting access",
                "expires_at": session.expires_at.isoformat(),
            })

        while True:
            message = await _receive_message(websocket)
            error = validate_signal(message, "technician", session.state == "active")
            if error:
                await websocket.send_json({"type": "error", "message": error})
                continue
            if message["type"] in RATE_LIMITED_SIGNAL_TYPES and not signaling_limiter.allow(f"{session.session_id}:technician"):
                await websocket.send_json({"type": "error", "message": "Too many WebRTC signaling messages. Try again shortly."})
                continue
            if message["type"] == "disconnect":
                await _terminate(session, "technician_disconnected")
                return
            await _forward(session.client_ws, websocket, message)
    except WebSocketDisconnect:
        if session:
            await _terminate(session, "technician_connection_lost")
    except asyncio.TimeoutError:
        await _reject(websocket, "Technician authentication timed out")
    except (ValueError, json.JSONDecodeError) as exc:
        await _reject(websocket, str(exc))
        if session:
            await _terminate(session, "invalid_technician_message")
    except Exception:
        logger.exception("Unexpected technician WebSocket error")
        if session:
            await _terminate(session, "server_error")
    finally:
        if session and session.session_id in sessions.by_id:
            sessions.detach(session, websocket)


async def _receive_message(websocket: WebSocket, timeout_seconds: Optional[int] = None) -> Dict[str, Any]:
    raw = websocket.receive_text()
    if timeout_seconds is not None:
        raw = await asyncio.wait_for(raw, timeout=timeout_seconds)
    else:
        raw = await raw
    if len(raw.encode("utf-8")) > settings.max_message_bytes:
        raise ValueError("Message is too large")
    message = json.loads(raw)
    if not isinstance(message, dict):
        raise ValueError("Message must be a JSON object")
    return message


async def _reject(websocket: WebSocket, message: str, close_code: int = 1008) -> None:
    with suppress(Exception):
        await websocket.send_json({"type": "error", "message": message})
    with suppress(Exception):
        await websocket.close(code=close_code)


async def _forward(target: Optional[WebSocket], sender: WebSocket, message: Dict[str, Any]) -> None:
    if target is None:
        await sender.send_json({"type": "error", "message": "The other participant is not connected"})
        return
    try:
        payload = sanitize_payload(message["type"], message.get("payload", {}))
    except ValueError as exc:
        await sender.send_json({"type": "error", "message": str(exc)})
        return
    await target.send_json({"type": message["type"], "payload": payload})


async def _terminate(session: RemoteSession, reason: str) -> None:
    active = sessions.remove(session.session_id)
    if not active:
        return
    _audit(reason, active)
    for socket in (active.client_ws, active.technician_ws):
        if socket:
            with suppress(Exception):
                await socket.send_json({"type": "session_ended", "reason": reason})
            with suppress(Exception):
                await socket.close(code=1000)


def _audit(event: str, session: Optional[RemoteSession]) -> None:
    # Never log connection codes, bearer tokens, SDP, ICE addresses, or diagnostic payloads.
    logger.info("security_event=%s session=%s", event, session.session_id[:8] if session else "unknown")


def _peer_key(address: str) -> str:
    # Uvicorn supplies the forwarded client address only for explicitly trusted proxy peers.
    return address or "unknown"


def _ice_servers_for_session(session: RemoteSession) -> list[Dict[str, Any]]:
    servers: list[Dict[str, Any]] = []
    if settings.stun_urls:
        servers.append({"urls": list(settings.stun_urls)})
    if settings.turn_urls and settings.turn_shared_secret:
        expiry = min(
            int(session.expires_at.timestamp()),
            int(time.time() + settings.turn_credential_ttl_seconds),
        )
        username = f"{expiry}:{session.session_id}"
        credential = base64.b64encode(
            hmac.new(settings.turn_shared_secret.encode("utf-8"), username.encode("utf-8"), hashlib.sha1).digest()
        ).decode("ascii")
        servers.append({"urls": list(settings.turn_urls), "username": username, "credential": credential})
    return servers


async def _expiry_sweeper() -> None:
    while True:
        await asyncio.sleep(5)
        for session_id in sessions.expired_ids():
            session = sessions.get_by_id(session_id)
            if session:
                await _terminate(session, "session_expired")


