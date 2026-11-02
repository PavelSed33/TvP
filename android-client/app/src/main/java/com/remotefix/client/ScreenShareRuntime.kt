package com.remotefix.client

import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-process bridge between the long-running capture service and session signaling. */
object ScreenShareRuntime {
    private val mutableStatus = MutableStateFlow(ScreenShareState.STOPPED)
    val status: StateFlow<ScreenShareState> = mutableStatus.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = mutableError.asStateFlow()
    @Volatile private var boundSessionId: String? = null

    @Volatile
    var sendSignal: ((String, JSONObject) -> Boolean)? = null

    fun bindSession(sessionId: String?) {
        boundSessionId = sessionId
        mutableStatus.value = ScreenShareState.STOPPED
        mutableError.value = null
    }

    fun updateStatus(sessionId: String, status: ScreenShareState) {
        if (sessionId == boundSessionId) mutableStatus.value = status
    }

    fun updateError(sessionId: String, error: String?) {
        if (sessionId == boundSessionId) mutableError.value = error
    }

    fun send(sessionId: String, type: String, payload: JSONObject = JSONObject()): Boolean {
        if (sessionId != boundSessionId) return false
        val message = JSONObject().put("type", type)
        if (type != "screen_share_ready" && type != "screen_share_stopped") message.put("payload", payload)
        return sendSignal?.invoke(sessionId, message) == true
    }
}
