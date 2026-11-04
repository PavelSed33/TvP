package com.remotefix.client

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.max
import kotlin.math.min

/** Owns MediaProjection and WebRTC while the user-approved screen share is active. */
class ScreenShareService : Service() {
    private var supportSessionId: String? = null
    private var capturer: ScreenCapturerAndroid? = null
    private var eglBase: EglBase? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private val pendingSignals = CopyOnWriteArrayList<String>()
    private val pendingLocalIceCandidates = CopyOnWriteArrayList<IceCandidate>()
    @Volatile private var remoteDescriptionReady = false
    @Volatile private var localDescriptionReady = false
    @Volatile private var offerBeingApplied = false
    @Volatile private var terminalStatus = ScreenShareState.STOPPED
    @Volatile private var stopping = false
    @Volatile private var disposed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startCapture(intent)
            ACTION_SIGNAL -> {
                val signalSessionId = intent.getStringExtra(EXTRA_SESSION_ID)
                if (supportSessionId == null) {
                    stopSelf(startId)
                } else if (signalSessionId == supportSessionId && capturer != null && !stopping && !disposed) {
                    receiveSignal(intent.getStringExtra(EXTRA_SIGNAL).orEmpty())
                } else if (capturer == null && signalSessionId == supportSessionId) {
                    stopSelf(startId)
                }
            }
            ACTION_STOP -> {
                if (supportSessionId == null) {
                    stopSelf(startId)
                } else if (intent.getStringExtra(EXTRA_SESSION_ID) == supportSessionId) {
                    stopSharing(notifyTechnician = true, finalState = ScreenShareState.STOPPED)
                }
            }
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun startCapture(intent: Intent) {
        if (disposed) return
        val requestedSessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        if (requestedSessionId.isBlank()) {
            fail(getString(R.string.error_screen_share_failed))
            return
        }
        if (capturer != null) {
            if (requestedSessionId != supportSessionId) {
                ScreenShareRuntime.updateError(requestedSessionId, "Another screen-sharing service is still stopping. Try again shortly.")
                ScreenShareRuntime.updateStatus(requestedSessionId, ScreenShareState.ERROR)
                stopSharing(notifyTechnician = false, finalState = ScreenShareState.STOPPED)
            }
            return
        }
        supportSessionId = requestedSessionId
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val permissionData = intent.intentExtra(EXTRA_PERMISSION_DATA)
        if (resultCode != Activity.RESULT_OK || permissionData == null) {
            fail(getString(R.string.error_screen_share_failed))
            return
        }

        try {
            createNotificationChannel()
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                0
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)

            updateShareStatus(ScreenShareState.STARTING)
            updateShareError(null)
            val iceServers = intent.getStringExtra(EXTRA_ICE_SERVERS).orEmpty()
            val initialSignals = intent.getStringArrayListExtra(EXTRA_INITIAL_SIGNALS).orEmpty()
            if (initialSignals.size > MAX_PENDING_SIGNAL_MESSAGES) {
                fail("Too many WebRTC messages arrived before screen sharing started.")
                return
            }
            pendingSignals.addAll(initialSignals)
            initializePeerConnectionFactory()
            createPeerConnection(iceServers)
            startMediaProjection(permissionData)
            updateShareStatus(ScreenShareState.WAITING_FOR_TECHNICIAN)
            if (!sendSessionSignal("screen_share_ready")) {
                fail("The support signaling connection is unavailable.")
                return
            }
            flushPendingSignals()
        } catch (error: Exception) {
            fail(error.localizedMessage ?: getString(R.string.error_screen_share_failed))
        }
    }

    private fun initializePeerConnectionFactory() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(applicationContext).createInitializationOptions(),
        )
        eglBase = EglBase.create()
        surfaceTextureHelper = SurfaceTextureHelper.create(
            "RemoteFixScreenCapture",
            requireNotNull(eglBase).eglBaseContext,
        )
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(requireNotNull(eglBase).eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(requireNotNull(eglBase).eglBaseContext))
            .createPeerConnectionFactory()
        videoSource = requireNotNull(peerConnectionFactory).createVideoSource(true)
    }

    private fun createPeerConnection(rawIceServers: String) {
        val factory = requireNotNull(peerConnectionFactory)
        val servers = parseIceServers(rawIceServers)
        val configuration = PeerConnection.RTCConfiguration(servers)
        configuration.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        peerConnection = factory.createPeerConnection(configuration, object : PeerConnection.Observer {
            override fun onSignalingChange(newState: PeerConnection.SignalingState) = Unit

            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                when (newState) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> updateShareStatus(ScreenShareState.STREAMING)
                    PeerConnection.IceConnectionState.CHECKING,
                    PeerConnection.IceConnectionState.DISCONNECTED,
                    PeerConnection.IceConnectionState.FAILED -> updateShareStatus(ScreenShareState.CONNECTING)
                    else -> Unit
                }
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) = Unit

            override fun onIceCandidate(candidate: IceCandidate) {
                if (!localDescriptionReady) {
                    val queued = synchronized(pendingLocalIceCandidates) {
                        if (pendingLocalIceCandidates.size >= MAX_PENDING_SIGNAL_MESSAGES) false
                        else {
                            pendingLocalIceCandidates += candidate
                            true
                        }
                    }
                    if (!queued) fail("Too many local WebRTC ICE candidates were generated.")
                    return
                }
                sendLocalIceCandidate(candidate)
            }

            private fun sendLocalIceCandidate(candidate: IceCandidate) {
                val payload = JSONObject()
                    .put("candidate", candidate.sdp)
                    .put("sdp_mid", candidate.sdpMid ?: JSONObject.NULL)
                    .put("sdp_mline_index", candidate.sdpMLineIndex.takeIf { it >= 0 } ?: JSONObject.NULL)
                if (!sendSessionSignal("ice_candidate", payload)) {
                    fail("The support signaling connection was lost.")
                }
            }

            override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) = Unit
            override fun onAddStream(stream: org.webrtc.MediaStream) = Unit
            override fun onRemoveStream(stream: org.webrtc.MediaStream) = Unit
            override fun onDataChannel(dataChannel: org.webrtc.DataChannel) = Unit
            override fun onRenegotiationNeeded() = Unit
        }) ?: throw IllegalStateException("Could not create a WebRTC peer connection")
    }

    private fun startMediaProjection(permissionData: Intent) {
        val source = requireNotNull(videoSource)
        val helper = requireNotNull(surfaceTextureHelper)
        val displaySize = captureSize()
        val screenCapturer = ScreenCapturerAndroid(permissionData, object : MediaProjection.Callback() {
            override fun onStop() {
                stopSharing(notifyTechnician = true, finalState = ScreenShareState.STOPPED)
            }
        })
        capturer = screenCapturer
        val track = requireNotNull(peerConnectionFactory).createVideoTrack(VIDEO_TRACK_ID, source)
        videoTrack = track
        track.setEnabled(true)
        requireNotNull(peerConnection).addTrack(track, listOf(STREAM_ID))
        screenCapturer.initialize(helper, applicationContext, source.capturerObserver)
        screenCapturer.startCapture(displaySize.first, displaySize.second, CAPTURE_FPS)
    }

    private fun captureSize(): Pair<Int, Int> {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        val longestSide = max(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(2)
        val scale = min(1f, MAX_CAPTURE_DIMENSION.toFloat() / longestSide)
        val width = (metrics.widthPixels * scale).toInt().coerceAtLeast(2) and -2
        val height = (metrics.heightPixels * scale).toInt().coerceAtLeast(2) and -2
        return width to height
    }

    private fun parseIceServers(raw: String): List<PeerConnection.IceServer> {
        val input = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        return buildList {
            for (index in 0 until input.length()) {
                val item = input.optJSONObject(index) ?: continue
                val urls = when (val value = item.opt("urls")) {
                    is String -> listOf(value)
                    is JSONArray -> (0 until value.length()).mapNotNull(value::optString)
                    else -> emptyList()
                }.filter {
                    it.startsWith("stun:") || it.startsWith("stuns:") ||
                        it.startsWith("turn:") || it.startsWith("turns:")
                }
                if (urls.isEmpty()) continue
                val builder = PeerConnection.IceServer.builder(urls)
                builder.setUsername(item.optString("username"))
                builder.setPassword(item.optString("credential"))
                add(builder.createIceServer())
            }
        }
    }

    private fun receiveSignal(raw: String) {
        if (raw.isBlank() || disposed || stopping) return
        val message = runCatching { JSONObject(raw) }.getOrNull() ?: return
        if (peerConnection == null || capturer == null) {
            if (!queuePendingSignal(raw)) fail("Too many WebRTC messages are waiting for screen sharing.")
            return
        }
        when (message.optString("type")) {
            "offer" -> applyRemoteOffer(message.optJSONObject("payload")?.optString("sdp").orEmpty())
            "ice_candidate" -> addRemoteIceCandidate(message.optJSONObject("payload"))
        }
    }

    private fun flushPendingSignals() {
        val queued = synchronized(pendingSignals) {
            pendingSignals.toList().also { pendingSignals.clear() }
        }
        queued.forEach(::receiveSignal)
    }

    private fun applyRemoteOffer(sdp: String) {
        val connection = peerConnection ?: return
        if (sdp.isBlank() || offerBeingApplied || stopping) return
        offerBeingApplied = true
        remoteDescriptionReady = false
        localDescriptionReady = false
        synchronized(pendingLocalIceCandidates) { pendingLocalIceCandidates.clear() }
        connection.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription) = Unit
            override fun onSetSuccess() {
                offerBeingApplied = false
                remoteDescriptionReady = true
                flushPendingSignals()
                connection.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(answer: SessionDescription) {
                        connection.setLocalDescription(object : SdpObserver {
                            override fun onCreateSuccess(description: SessionDescription) = Unit
                            override fun onSetSuccess() {
                                val sent = sendSessionSignal("answer", JSONObject().put("sdp", answer.description))
                                if (!sent) fail("The support signaling connection was lost.")
                                else {
                                    localDescriptionReady = true
                                    updateShareStatus(ScreenShareState.CONNECTING)
                                    flushPendingLocalIceCandidates()
                                }
                            }
                            override fun onCreateFailure(error: String) = fail(error)
                            override fun onSetFailure(error: String) = fail(error)
                        }, answer)
                    }
                    override fun onSetSuccess() = Unit
                    override fun onCreateFailure(error: String) = fail(error)
                    override fun onSetFailure(error: String) = fail(error)
                }, MediaConstraints())
            }
            override fun onCreateFailure(error: String) = fail(error)
            override fun onSetFailure(error: String) {
                offerBeingApplied = false
                fail(error)
            }
        }, SessionDescription(SessionDescription.Type.OFFER, sdp))
    }

    private fun addRemoteIceCandidate(payload: JSONObject?) {
        payload ?: return
        val candidate = payload.optString("candidate")
        if (candidate.isBlank()) return
        if (!remoteDescriptionReady) {
            val queued = queuePendingSignal(JSONObject().put("type", "ice_candidate").put("payload", payload).toString())
            if (!queued) fail("Too many remote WebRTC ICE candidates are waiting to be applied.")
            return
        }
        val mid = payload.optString("sdp_mid").takeIf(String::isNotBlank)
        val lineIndex = payload.optInt("sdp_mline_index", -1)
        val ice = IceCandidate(mid, lineIndex.coerceAtLeast(0), candidate)
        if (peerConnection?.addIceCandidate(ice) == false) {
            fail("Could not apply a WebRTC ICE candidate.")
        }
    }

    private fun queuePendingSignal(raw: String): Boolean = synchronized(pendingSignals) {
        if (pendingSignals.size >= MAX_PENDING_SIGNAL_MESSAGES) false
        else {
            pendingSignals += raw
            true
        }
    }

    private fun flushPendingLocalIceCandidates() {
        val queued = synchronized(pendingLocalIceCandidates) {
            pendingLocalIceCandidates.toList().also { pendingLocalIceCandidates.clear() }
        }
        queued.forEach { candidate ->
            val payload = JSONObject()
                .put("candidate", candidate.sdp)
                .put("sdp_mid", candidate.sdpMid ?: JSONObject.NULL)
                .put("sdp_mline_index", candidate.sdpMLineIndex.takeIf { it >= 0 } ?: JSONObject.NULL)
            if (!sendSessionSignal("ice_candidate", payload)) {
                fail("The support signaling connection was lost.")
                return
            }
        }
    }

    private fun fail(reason: String) {
        if (disposed || stopping) return
        stopping = true
        terminalStatus = ScreenShareState.ERROR
        updateShareError(reason)
        updateShareStatus(ScreenShareState.ERROR)
        sendSessionSignal("screen_share_stopped")
        stopSelf()
    }

    private fun stopSharing(notifyTechnician: Boolean, finalState: ScreenShareState) {
        if (disposed || stopping) return
        stopping = true
        terminalStatus = finalState
        updateShareError(null)
        if (notifyTechnician) sendSessionSignal("screen_share_stopped")
        stopSelf()
    }

    override fun onDestroy() {
        disposed = true
        runCatching { capturer?.stopCapture() }
        runCatching { capturer?.dispose() }
        capturer = null
        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null
        videoTrack?.dispose()
        videoTrack = null
        videoSource?.dispose()
        videoSource = null
        peerConnectionFactory?.dispose()
        peerConnectionFactory = null
        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null
        eglBase?.release()
        eglBase = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (terminalStatus != ScreenShareState.ERROR) updateShareStatus(ScreenShareState.STOPPED)
        supportSessionId = null
        super.onDestroy()
    }

    private fun updateShareStatus(status: ScreenShareState) {
        supportSessionId?.let { ScreenShareRuntime.updateStatus(it, status) }
    }

    private fun updateShareError(error: String?) {
        supportSessionId?.let { ScreenShareRuntime.updateError(it, error) }
    }

    private fun sendSessionSignal(type: String, payload: JSONObject = JSONObject()): Boolean =
        supportSessionId?.let { ScreenShareRuntime.send(it, type, payload) } == true

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
            .apply { description = getString(R.string.notification_channel_description) }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            STOP_PENDING_INTENT_REQUEST,
            Intent(this, ScreenShareService::class.java)
                .setAction(ACTION_STOP)
                .putExtra(EXTRA_SESSION_ID, supportSessionId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openIntent = PendingIntent.getActivity(
            this,
            OPEN_PENDING_INTENT_REQUEST,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_remotefix)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.screen_share_notification))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(R.drawable.ic_stat_remotefix, getString(R.string.screen_share_notification_stop), stopIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
    }

    private fun Intent.intentExtra(key: String): Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(key, Intent::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(key)
    }

    companion object {
        private const val CHANNEL_ID = "remotefix_screen_sharing"
        private const val NOTIFICATION_ID = 4813
        private const val STOP_PENDING_INTENT_REQUEST = 4814
        private const val OPEN_PENDING_INTENT_REQUEST = 4815
        private const val ACTION_START = "com.remotefix.client.action.START_SCREEN_SHARE"
        private const val ACTION_SIGNAL = "com.remotefix.client.action.SCREEN_SHARE_SIGNAL"
        private const val ACTION_STOP = "com.remotefix.client.action.STOP_SCREEN_SHARE"
        private const val EXTRA_RESULT_CODE = "projection_result_code"
        private const val EXTRA_PERMISSION_DATA = "projection_permission_data"
        private const val EXTRA_SESSION_ID = "support_session_id"
        private const val EXTRA_ICE_SERVERS = "ice_servers"
        private const val EXTRA_INITIAL_SIGNALS = "initial_signals"
        private const val EXTRA_SIGNAL = "signal"
        private const val VIDEO_TRACK_ID = "remotefix-screen"
        private const val STREAM_ID = "remotefix-screen-stream"
        private const val MAX_CAPTURE_DIMENSION = 1280
        private const val CAPTURE_FPS = 15
        private const val MAX_PENDING_SIGNAL_MESSAGES = 128

        fun start(
            context: Context,
            sessionId: String,
            resultCode: Int,
            permissionData: Intent,
            iceServers: String,
            initialSignals: ArrayList<String>,
        ) {
            val intent = Intent(context, ScreenShareService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_SESSION_ID, sessionId)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_PERMISSION_DATA, permissionData)
                .putExtra(EXTRA_ICE_SERVERS, iceServers)
                .putStringArrayListExtra(EXTRA_INITIAL_SIGNALS, initialSignals)
            ContextCompat.startForegroundService(context, intent)
        }

        fun signal(context: Context, sessionId: String, raw: String) {
            context.startService(
                Intent(context, ScreenShareService::class.java)
                    .setAction(ACTION_SIGNAL)
                    .putExtra(EXTRA_SESSION_ID, sessionId)
                    .putExtra(EXTRA_SIGNAL, raw),
            )
        }

        fun stop(context: Context, sessionId: String?) {
            val intent = Intent(context, ScreenShareService::class.java)
                .setAction(ACTION_STOP)
                .putExtra(EXTRA_SESSION_ID, sessionId)
            runCatching { context.startService(intent) }
        }
    }
}
