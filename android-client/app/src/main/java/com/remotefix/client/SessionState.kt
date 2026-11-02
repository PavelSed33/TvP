package com.remotefix.client

import androidx.annotation.StringRes

enum class SessionStatus(@param:StringRes @get:StringRes val labelRes: Int) {
    IDLE(R.string.status_idle),
    CREATING(R.string.status_creating),
    WAITING_FOR_TECHNICIAN(R.string.status_waiting),
    AWAITING_CLIENT_CONSENT(R.string.status_consent),
    ACTIVE(R.string.status_active),
    ENDED(R.string.status_ended),
    ERROR(R.string.status_error),
}

enum class ScreenShareState(@param:StringRes @get:StringRes val labelRes: Int) {
    STOPPED(R.string.screen_share_stopped),
    STARTING(R.string.screen_share_starting),
    WAITING_FOR_TECHNICIAN(R.string.screen_share_waiting),
    CONNECTING(R.string.screen_share_connecting),
    STREAMING(R.string.screen_share_streaming),
    ERROR(R.string.screen_share_error),
}

data class DeviceDiagnostics(
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val appVersion: String,
    val batteryPercent: Int?,
    val freeRamMb: Long,
    val freeStorageMb: Long,
    val networkType: String,
    val wifiEnabled: Boolean,
    val checks: List<DiagnosticCheck>,
)

data class DiagnosticCheck(val name: String, val status: String)

data class ClientUiState(
    val serverUrl: String = BuildConfig.DEFAULT_SERVER_URL,
    val status: SessionStatus = SessionStatus.IDLE,
    val busy: Boolean = false,
    val code: String? = null,
    val expiresAt: String? = null,
    val connectionRequest: Boolean = false,
    val sessionActive: Boolean = false,
    val screenPermissionGranted: Boolean = false,
    val screenShareState: ScreenShareState = ScreenShareState.STOPPED,
    val screenShareError: String? = null,
    val diagnostics: DeviceDiagnostics? = null,
    val error: String? = null,
    val events: List<String> = emptyList(),
)
