package com.remotefix.client

import android.app.Application
import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class RemoteFixViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences(PREFERENCES, android.content.Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val _state = MutableStateFlow(ClientUiState(serverUrl = loadServerUrl()))
    val state: StateFlow<ClientUiState> = _state.asStateFlow()

    @Volatile
    private var sessionId: String? = null
    private var clientToken: String? = null
    @Volatile
    private var socket: WebSocket? = null
    private var socketAuthenticated = false
    private var iceServersJson = "[]"
    private val pendingScreenSignals = mutableListOf<String>()
    private val pendingScreenSignalsLock = Any()

    init {
        ScreenShareRuntime.bindSession(null)
        ScreenShareRuntime.sendSignal = { expectedSessionId, message ->
            if (sessionId != expectedSessionId) false else socket?.send(message.toString()) == true
        }
        viewModelScope.launch {
            ScreenShareRuntime.status.collect { status ->
                update {
                    it.copy(
                        screenShareState = status,
                        screenPermissionGranted = status != ScreenShareState.STOPPED && status != ScreenShareState.ERROR,
                    )
                }
            }
        }
        viewModelScope.launch {
            ScreenShareRuntime.error.collect { error -> update { it.copy(screenShareError = error) } }
        }
    }

    fun saveServerUrl(input: String) {
        val normalized = input.trim().trimEnd('/')
        val uri = runCatching { Uri.parse(normalized) }.getOrNull()
        if (uri == null || uri.scheme != "https" || uri.host.isNullOrBlank() ||
            !uri.encodedUserInfo.isNullOrEmpty() || !uri.query.isNullOrEmpty() || !uri.fragment.isNullOrEmpty() ||
            uri.path.orEmpty().isNotEmpty() && uri.path != "/"
        ) {
            update { it.copy(error = getApplication<Application>().getString(R.string.error_server_url)) }
            return
        }
        preferences.edit().putString(SERVER_URL_KEY, normalized).apply()
        update {
            it.copy(serverUrl = normalized, error = null)
                .withEvent(getApplication<Application>().getString(R.string.event_server_saved))
        }
    }

    fun createSession() {
        if (_state.value.busy || sessionId != null) return
        if (!SessionPresenceService.hasNotificationAccess(getApplication())) {
            update { it.copy(error = getApplication<Application>().getString(R.string.error_notifications_required)) }
            return
        }
        val baseUrl = _state.value.serverUrl
        try {
            SessionPresenceService.start(getApplication())
        } catch (error: RuntimeException) {
            update { it.copy(status = SessionStatus.ERROR, error = error.localizedMessage ?: "Could not start visible session") }
            return
        }
        update { it.copy(status = SessionStatus.CREATING, busy = true, error = null) }
        val request = Request.Builder()
            .url("$baseUrl/api/sessions")
            .post("{}".toRequestBody(jsonMediaType))
            .build()
        http.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                SessionPresenceService.stop(getApplication())
                update {
                    it.copy(status = SessionStatus.ERROR, busy = false, error = e.localizedMessage ?: "Network error")
                        .withEvent(getApplication<Application>().getString(R.string.event_create_failed))
                }
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use { result ->
                    val body = result.body?.string().orEmpty()
                    if (!result.isSuccessful) {
                        failCreate("HTTP ${result.code}: ${serverError(body)}")
                        return
                    }
                    runCatching { JSONObject(body) }.onSuccess { data ->
                        val createdId = data.optString("session_id")
                        val token = data.optString("client_token")
                        val code = data.optString("code")
                        if (createdId.isBlank() || token.isBlank() || !code.matches(Regex("[0-9]{6}"))) {
                            failCreate("Invalid session response")
                            return
                        }
                        sessionId = createdId
                        ScreenShareRuntime.bindSession(createdId)
                        clientToken = token
                        iceServersJson = data.optJSONArray("ice_servers")?.toString() ?: "[]"
                        update {
                            it.copy(
                                status = SessionStatus.WAITING_FOR_TECHNICIAN,
                                busy = false,
                                code = code,
                                expiresAt = data.optString("expires_at"),
                                error = null,
                            ).withEvent(getApplication<Application>().getString(R.string.event_code_created))
                        }
                        connectClient(baseUrl, createdId, token)
                    }.onFailure { error -> failCreate(error.localizedMessage ?: "Invalid server response") }
                }
            }
        })
    }

    fun approveConnection(approved: Boolean) {
        if (!socketAuthenticated) return
        val sent = socket?.send(JSONObject().put("type", "consent").put("approved", approved).toString()) == true
        if (!sent) {
            update { it.copy(error = getApplication<Application>().getString(R.string.error_send_failed)) }
            return
        }
        update {
            it.copy(connectionRequest = false)
                .withEvent(getApplication<Application>().getString(if (approved) R.string.event_consent_approved else R.string.event_consent_denied))
        }
    }

    fun onScreenPermissionResult(resultCode: Int, permissionData: Intent?) {
        val activeSessionId = sessionId?.takeIf { _state.value.sessionActive }
        val sessionIsStillActive = activeSessionId != null
        val permissionWasGrantedForActiveSession = resultCode == Activity.RESULT_OK && permissionData != null && sessionIsStillActive
        val event = when {
            !sessionIsStillActive -> R.string.event_screen_permission_ignored
            permissionWasGrantedForActiveSession -> R.string.event_screen_permission_granted
            else -> R.string.event_screen_permission_denied
        }
        if (!permissionWasGrantedForActiveSession) {
            update {
                it.copy(screenPermissionGranted = false)
                    .withEvent(getApplication<Application>().getString(event))
            }
            return
        }

        val pending = synchronized(pendingScreenSignalsLock) {
            pendingScreenSignals.toList().also { pendingScreenSignals.clear() }
        }
        ScreenShareRuntime.updateError(requireNotNull(activeSessionId), null)
        update {
            it.copy(screenPermissionGranted = true, screenShareState = ScreenShareState.STARTING, screenShareError = null)
                .withEvent(getApplication<Application>().getString(event))
        }
        runCatching {
            ScreenShareService.start(
                getApplication(),
                requireNotNull(activeSessionId),
                resultCode,
                requireNotNull(permissionData),
                iceServersJson,
                ArrayList(pending),
            )
        }.onFailure { error ->
            val reason = error.localizedMessage ?: getApplication<Application>().getString(R.string.error_screen_share_failed)
            ScreenShareRuntime.updateStatus(requireNotNull(activeSessionId), ScreenShareState.ERROR)
            ScreenShareRuntime.updateError(requireNotNull(activeSessionId), reason)
            update { it.copy(screenShareState = ScreenShareState.ERROR, screenShareError = reason) }
        }
    }

    fun stopScreenShare() {
        val activeSessionId = sessionId
        synchronized(pendingScreenSignalsLock) { pendingScreenSignals.clear() }
        ScreenShareService.stop(getApplication(), activeSessionId)
        activeSessionId?.let {
            ScreenShareRuntime.updateStatus(it, ScreenShareState.STOPPED)
            ScreenShareRuntime.updateError(it, null)
        }
        update { it.copy(screenPermissionGranted = false, screenShareState = ScreenShareState.STOPPED, screenShareError = null) }
    }

    fun onNotificationsDenied() {
        update { it.copy(error = getApplication<Application>().getString(R.string.error_notifications_required)) }
    }

    fun ensureSessionNotificationVisible() {
        if (sessionId != null && !SessionPresenceService.hasNotificationAccess(getApplication())) {
            disconnect()
        }
    }

    fun collectDiagnostics() {
        viewModelScope.launch(Dispatchers.IO) {
            val diagnostics = DiagnosticsCollector.collect(
                getApplication(),
                serverConnected = socketAuthenticated,
                screenPermissionGranted = _state.value.screenPermissionGranted,
                sessionServiceActive = sessionId != null,
            )
            update {
                it.copy(diagnostics = diagnostics)
                    .withEvent(getApplication<Application>().getString(R.string.event_diagnostics_collected))
            }
            if (_state.value.sessionActive && socketAuthenticated) {
                socket?.send(JSONObject().put("type", "device_info").put("payload", diagnostics.toDeviceJson()).toString())
                socket?.send(JSONObject().put("type", "diagnostics").put("payload", diagnostics.toReportJson()).toString())
            }
        }
    }

    fun disconnect() {
        stopScreenShare()
        SessionPresenceService.stop(getApplication())
        val activeSocket = socket
        socket = null
        socketAuthenticated = false
        activeSocket?.send(JSONObject().put("type", "disconnect").toString())
        activeSocket?.close(1000, "Client ended session")
        sessionId = null
        ScreenShareRuntime.bindSession(null)
        clientToken = null
        iceServersJson = "[]"
        update {
            it.copy(
                status = SessionStatus.ENDED,
                busy = false,
                code = null,
                expiresAt = null,
                connectionRequest = false,
                sessionActive = false,
                screenPermissionGranted = false,
                screenShareState = ScreenShareState.STOPPED,
                screenShareError = null,
            ).withEvent(getApplication<Application>().getString(R.string.event_disconnected))
        }
    }

    private fun connectClient(baseUrl: String, id: String, token: String) {
        val wsUrl = baseUrl.replaceFirst("https://", "wss://") + "/ws/client/$id"
        val request = Request.Builder().url(wsUrl).build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (webSocket !== socket) return
                val sent = webSocket.send(JSONObject().put("type", "authenticate").put("token", token).toString())
                if (!sent) handleSocketFailure(webSocket, "Could not send client authentication")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (webSocket !== socket) return
                handleServerMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (webSocket !== socket) return
                handleSocketFailure(webSocket, t.localizedMessage ?: "WebSocket connection failed")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket !== socket) return
                finishRemoteSession(reason.ifBlank { "Connection closed" })
            }
        })
    }

    private fun handleServerMessage(raw: String) {
        val message = runCatching { JSONObject(raw) }.getOrNull() ?: run {
            update { it.copy(error = "Invalid message from server") }
            return
        }
        when (message.optString("type")) {
            "authenticated" -> {
                socketAuthenticated = true
                if (!updateSessionNotification(R.string.notification_waiting)) return
                update {
                    it.copy(status = SessionStatus.WAITING_FOR_TECHNICIAN)
                        .withEvent(getApplication<Application>().getString(R.string.event_server_connected))
                }
            }
            "connection_request" -> {
                if (!updateSessionNotification(R.string.notification_request, alert = true)) return
                val sessionExpiresAt = message.optString("expires_at").takeIf(String::isNotBlank)
                update {
                    it.copy(
                        status = SessionStatus.AWAITING_CLIENT_CONSENT,
                        connectionRequest = true,
                        expiresAt = sessionExpiresAt ?: it.expiresAt,
                    )
                        .withEvent(getApplication<Application>().getString(R.string.event_connection_request))
                }
            }
            "consent_result" -> if (message.optBoolean("approved")) {
                if (!updateSessionNotification(R.string.notification_active)) return
                update {
                    it.copy(status = SessionStatus.ACTIVE, connectionRequest = false, sessionActive = true)
                        .withEvent(getApplication<Application>().getString(R.string.event_session_active))
                }
            } else {
                finishRemoteSession(getApplication<Application>().getString(R.string.event_consent_denied_remote))
            }
            "offer", "ice_candidate" -> {
                val activeSessionId = sessionId ?: return
                val relayToService = _state.value.screenPermissionGranted
                if (relayToService) {
                    runCatching { ScreenShareService.signal(getApplication(), activeSessionId, message.toString()) }
                        .onFailure { error -> update { it.copy(screenShareError = error.localizedMessage) } }
                } else {
                    synchronized(pendingScreenSignalsLock) {
                        if (_state.value.screenPermissionGranted) {
                            runCatching { ScreenShareService.signal(getApplication(), activeSessionId, message.toString()) }
                                .onFailure { error -> update { it.copy(screenShareError = error.localizedMessage) } }
                        } else if (pendingScreenSignals.size < MAX_PENDING_SCREEN_SIGNALS) {
                            pendingScreenSignals += message.toString()
                        }
                    }
                }
            }
            "session_ended" -> finishRemoteSession(message.optString("reason"))
            "error" -> update { it.copy(error = message.optString("message", "Server rejected the request")) }
        }
    }

    private fun finishRemoteSession(reason: String) {
        stopScreenShare()
        SessionPresenceService.stop(getApplication())
        socket = null
        socketAuthenticated = false
        sessionId = null
        ScreenShareRuntime.bindSession(null)
        clientToken = null
        iceServersJson = "[]"
        update {
            it.copy(
                status = SessionStatus.ENDED,
                busy = false,
                code = null,
                expiresAt = null,
                connectionRequest = false,
                sessionActive = false,
                screenPermissionGranted = false,
                screenShareState = ScreenShareState.STOPPED,
                screenShareError = null,
                error = null,
            ).withEvent(getApplication<Application>().getString(R.string.event_session_ended, reason))
        }
    }

    private fun handleSocketFailure(webSocket: WebSocket, reason: String) {
        if (webSocket !== socket) return
        stopScreenShare()
        SessionPresenceService.stop(getApplication())
        socket = null
        socketAuthenticated = false
        sessionId = null
        ScreenShareRuntime.bindSession(null)
        clientToken = null
        iceServersJson = "[]"
        update {
            it.copy(
                status = SessionStatus.ERROR,
                busy = false,
                code = null,
                expiresAt = null,
                connectionRequest = false,
                sessionActive = false,
                screenPermissionGranted = false,
                screenShareState = ScreenShareState.STOPPED,
                screenShareError = null,
                error = reason,
            ).withEvent(getApplication<Application>().getString(R.string.event_connection_lost))
        }
        webSocket.close(1001, "Connection failed")
    }

    private fun updateSessionNotification(messageRes: Int, alert: Boolean = false): Boolean {
        val application = getApplication<Application>()
        val visible = SessionPresenceService.update(application, application.getString(messageRes), alert)
        if (!visible) disconnect()
        return visible
    }

    private fun failCreate(reason: String) {
        SessionPresenceService.stop(getApplication())
        update {
            it.copy(status = SessionStatus.ERROR, busy = false, error = reason)
                .withEvent(getApplication<Application>().getString(R.string.event_create_failed))
        }
    }

    private fun serverError(body: String): String = runCatching {
        JSONObject(body).optString("detail").ifBlank { "Request failed" }
    }.getOrDefault("Request failed")

    private fun loadServerUrl(): String = preferences.getString(SERVER_URL_KEY, BuildConfig.DEFAULT_SERVER_URL)
        ?.trimEnd('/') ?: BuildConfig.DEFAULT_SERVER_URL

    private fun update(reducer: (ClientUiState) -> ClientUiState) {
        _state.value = reducer(_state.value)
    }

    private fun ClientUiState.withEvent(event: String): ClientUiState = copy(events = (listOf(event) + events).take(MAX_EVENTS))

    private fun DeviceDiagnostics.toDeviceJson(): JSONObject = JSONObject()
        .put("manufacturer", manufacturer)
        .put("model", model)
        .put("android_version", androidVersion)
        .put("app_version", appVersion)
        .put("battery_percent", batteryPercent ?: JSONObject.NULL)
        .put("free_ram_mb", freeRamMb)
        .put("free_storage_mb", freeStorageMb)
        .put("network_type", networkType)
        .put("wifi_enabled", wifiEnabled)
        .put(
            "app_status",
            when {
                _state.value.screenShareState == ScreenShareState.STREAMING -> "streaming"
                socketAuthenticated -> "connected"
                else -> "ready"
            },
        )

    private fun DeviceDiagnostics.toReportJson(): JSONObject {
        val jsonChecks = JSONArray()
        checks.forEach { check ->
            jsonChecks.put(JSONObject().put("name", check.name).put("status", check.status))
        }
        return JSONObject().put("checks", jsonChecks)
    }

    override fun onCleared() {
        ScreenShareService.stop(getApplication(), sessionId)
        ScreenShareRuntime.bindSession(null)
        ScreenShareRuntime.sendSignal = null
        SessionPresenceService.stop(getApplication())
        socket?.close(1001, "App closed")
        socket = null
        clientToken = null
        sessionId = null
        super.onCleared()
    }

    companion object {
        private const val PREFERENCES = "remotefix_preferences"
        private const val SERVER_URL_KEY = "server_url"
        private const val MAX_EVENTS = 30
        private const val MAX_PENDING_SCREEN_SIGNALS = 64
    }
}
