package com.remotemonitor.app.services

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.remotemonitor.app.R
import com.remotemonitor.app.RemoteMonitorApp
import com.remotemonitor.app.data.RetrofitClient
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.*
import org.json.JSONObject
import org.webrtc.*
import java.net.URI
import java.util.concurrent.Executors

/**
 * MonitorService — the core foreground service.
 *
 * Responsibilities:
 *  • Maintains WSS Socket.IO connection to the signaling server
 *  • Manages WebRTC PeerConnection for screen/camera/mic streaming
 *  • Sends GPS location every 10 seconds
 *  • Pushes device status (battery, network, etc.) every 10 seconds
 *  • Auto-reconnects on network loss
 */
class MonitorService : LifecycleService() {

    companion object {
        private const val TAG              = "MonitorService"
        const val ACTION_START             = "com.remotemonitor.START"
        const val ACTION_START_BACKGROUND  = "com.remotemonitor.START_BACKGROUND"
        const val ACTION_STOP              = "com.remotemonitor.STOP"
        const val EXTRA_PROJECTION_DATA    = "projection_data"
        const val EXTRA_PROJECTION_RESULT  = "projection_result"
        const val NOTIFICATION_ID          = 1001

        fun buildStartIntent(
            context: Context,
            resultCode: Int,
            data: Intent,
        ) = Intent(context, MonitorService::class.java).apply {
            action = ACTION_START
            putExtra(EXTRA_PROJECTION_RESULT, resultCode)
            putExtra(EXTRA_PROJECTION_DATA, data)
        }
    }

    // ── Dependencies ───────────────────────────────────────────
    private val session by lazy { RemoteMonitorApp.instance.sessionManager }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Socket.IO ─────────────────────────────────────────────
    private var socket: Socket? = null
    private var reconnectJob: Job? = null

    // ── WebRTC ────────────────────────────────────────────────
    private var eglBase: EglBase?                    = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection?      = null
    private var localStream: MediaStream?            = null
    private var screenCapture: VideoCapturer?        = null
    private var cameraCapture: VideoCapturer?        = null
    private var videoSource: VideoSource?            = null
    private var audioSource: AudioSource?            = null
    private var mediaProjection: MediaProjection?    = null
    private var projectionData: Intent?              = null
    private val webRtcExecutor = Executors.newSingleThreadExecutor()

    // ── Helpers ───────────────────────────────────────────────
    private val locationHelper  by lazy { LocationHelper(this) }
    private val statusHelper    by lazy { DeviceStatusHelper(this) }

    // ─────────────────────────────────────────────────────────
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_PROJECTION_RESULT, -1)
                val projData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Intent>(EXTRA_PROJECTION_DATA)
                }
                projectionData = projData

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID, 
                        buildNotification(), 
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                    )
                } else {
                    startForeground(NOTIFICATION_ID, buildNotification())
                }

                if (projData != null && resultCode == Activity.RESULT_OK) {
                    val mpm = getSystemService(MediaProjectionManager::class.java)
                    mediaProjection = mpm.getMediaProjection(resultCode, projData)
                }

                initWebRtc()
                connectSocket()
                startStatusLoop()
                startLocationLoop()
            }
            ACTION_START_BACKGROUND -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID, 
                        buildNotification(), 
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                    )
                } else {
                    startForeground(NOTIFICATION_ID, buildNotification())
                }

                initWebRtc()
                connectSocket()
                startStatusLoop()
                startLocationLoop()
            }
        }

        return START_STICKY
    }

    // ─────────────────────────────────────────────────────────
    // WebRTC Initialisation
    // ─────────────────────────────────────────────────────────
    private fun initWebRtc() {
        eglBase = EglBase.create()
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(this.applicationContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase!!.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase!!.eglBaseContext))
            .createPeerConnectionFactory()

        buildLocalStream()
    }

    private fun buildLocalStream() {
        localStream = peerConnectionFactory!!.createLocalMediaStream("local_stream")

        // ── Screen capture track ──────────────────────────────
        if (projectionData != null) {
            screenCapture = ScreenCapturerAndroid(
                projectionData,
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        Log.d(TAG, "MediaProjection stopped")
                    }
                }
            )
            videoSource = peerConnectionFactory!!.createVideoSource(false)
            val videoTrack = peerConnectionFactory!!.createVideoTrack("screen_track", videoSource!!)
            localStream!!.addTrack(videoTrack)

            val surfaceTextureHelper = org.webrtc.SurfaceTextureHelper.create("CaptureThread", eglBase!!.eglBaseContext)
            screenCapture!!.initialize(surfaceTextureHelper, this.applicationContext, videoSource!!.capturerObserver)
            // Screen capture will be started on demand
        }

        // ── Camera track ──────────────────────────────
        cameraCapture = createCameraCapturer()
        if (cameraCapture != null) {
            val cameraVideoSource = peerConnectionFactory!!.createVideoSource(false)
            val cameraTrack = peerConnectionFactory!!.createVideoTrack("camera_track", cameraVideoSource)
            localStream!!.addTrack(cameraTrack)

            val cameraSurfaceHelper = org.webrtc.SurfaceTextureHelper.create("CameraThread", eglBase!!.eglBaseContext)
            cameraCapture!!.initialize(cameraSurfaceHelper, this.applicationContext, cameraVideoSource.capturerObserver)
            // Camera capture will be started on demand
        }

        // Microphone audio track will be initialized on demand
        
        Log.d(TAG, "Local stream built (video tracks only)")
    }

    private fun createCameraCapturer(): VideoCapturer? {
        val enumerator = Camera2Enumerator(this)
        val deviceNames = enumerator.deviceNames

        // Try front facing camera first
        for (deviceName in deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                return enumerator.createCapturer(deviceName, null)
            }
        }
        // Try back facing camera
        for (deviceName in deviceNames) {
            if (enumerator.isBackFacing(deviceName)) {
                return enumerator.createCapturer(deviceName, null)
            }
        }
        return null
    }

    // ─────────────────────────────────────────────────────────
    // Socket.IO Connection
    // ─────────────────────────────────────────────────────────
    private fun connectSocket() {
        val serverUrl   = session.serverUrl ?: return
        val deviceToken = session.deviceToken ?: return

        try {
            val options = IO.Options.builder()
                .setAuth(mapOf("deviceToken" to deviceToken))
                .setTransports(arrayOf("websocket"))
                .setReconnection(true)
                .setReconnectionAttempts(Int.MAX_VALUE)
                .setReconnectionDelay(2000)
                .setReconnectionDelayMax(10000)
                .build()

            val wsUrl = serverUrl
                .replace("https://", "wss://")
                .replace("http://",  "ws://")

            socket = IO.socket(URI.create("$wsUrl/device"), options)
            registerSocketListeners()
            socket!!.connect()
        } catch (e: Exception) {
            Log.e(TAG, "Socket connection failed", e)
            scheduleReconnect()
        }
    }

    private fun registerSocketListeners() {
        val s = socket ?: return

        s.on(Socket.EVENT_CONNECT) {
            Log.i(TAG, "Socket connected")
            reconnectJob?.cancel()
            startStatusLoop()
            startLocationLoop()
        }

        s.on(Socket.EVENT_DISCONNECT) { args ->
            Log.w(TAG, "Socket disconnected: ${args.firstOrNull()}")
            scheduleReconnect()
        }

        s.on(Socket.EVENT_CONNECT_ERROR) { args ->
            Log.e(TAG, "Socket error: ${args.firstOrNull()}")
        }

        // Viewer requests a WebRTC offer
        s.on("webrtc:request-offer") { args ->
            val viewerSocketId = (args.getOrNull(0) as? JSONObject)?.optString("viewerSocketId") ?: return@on
            serviceScope.launch { createOffer(viewerSocketId) }
        }
        
        // Viewer requests to switch camera
        s.on("webrtc:switch-camera") {
            try {
                (cameraCapture as? org.webrtc.CameraVideoCapturer)?.switchCamera(null)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to switch camera: ${e.message}")
            }
        }

        // Viewer sends ICE candidate
        s.on("webrtc:ice") { args ->
            val data      = args.getOrNull(0) as? JSONObject ?: return@on
            val candidate = data.optJSONObject("candidate") ?: return@on
            peerConnection?.addIceCandidate(
                IceCandidate(
                    candidate.optString("sdpMid"),
                    candidate.optInt("sdpMLineIndex"),
                    candidate.optString("candidate")
                )
            )
        }

        // Viewer sends answer
        s.on("webrtc:answer") { args ->
            val data = args.getOrNull(0) as? JSONObject ?: return@on
            val sdp  = data.optJSONObject("sdp") ?: return@on
            peerConnection?.setRemoteDescription(
                SimpleSdpObserver(),
                SessionDescription(SessionDescription.Type.ANSWER, sdp.optString("sdp"))
            )
        }
    }

    // ─────────────────────────────────────────────────────────
    // WebRTC Offer / PeerConnection
    // ─────────────────────────────────────────────────────────
    private suspend fun createOffer(viewerSocketId: String) = withContext(Dispatchers.IO) {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )

        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics    = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection?.dispose()
        peerConnection = peerConnectionFactory!!.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                val payload = JSONObject().apply {
                    put("candidate", JSONObject().apply {
                        put("candidate",     candidate.sdp)
                        put("sdpMid",        candidate.sdpMid)
                        put("sdpMLineIndex", candidate.sdpMLineIndex)
                    })
                    put("deviceToken", session.deviceToken)
                }
                socket?.emit("webrtc:ice", payload)
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Log.d(TAG, "ICE state: $state")
                if (state == PeerConnection.IceConnectionState.DISCONNECTED ||
                    state == PeerConnection.IceConnectionState.FAILED ||
                    state == PeerConnection.IceConnectionState.CLOSED) {
                    try {
                        screenCapture?.stopCapture()
                        cameraCapture?.stopCapture()
                        audioSource?.dispose()
                        audioSource = null
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to stop captures: ${e.message}")
                    }
                }
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState?)   = Unit
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) = Unit
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?)        = Unit
            override fun onAddStream(s: MediaStream?)                               = Unit
            override fun onRemoveStream(s: MediaStream?)                            = Unit
            override fun onDataChannel(d: DataChannel?)                             = Unit
            override fun onRenegotiationNeeded()                                    = Unit
            override fun onAddTrack(r: RtpReceiver?, s: Array<out MediaStream>?)    = Unit
            override fun onIceConnectionReceivingChange(r: Boolean)                 = Unit
        })

        // Start captures on demand
        if (screenCapture != null) {
            screenCapture!!.startCapture(
                resources.displayMetrics.widthPixels,
                resources.displayMetrics.heightPixels,
                30
            )
        }
        if (cameraCapture != null) {
            cameraCapture!!.startCapture(1280, 720, 30)
        }

        // Initialize audio source on demand
        if (audioSource == null) {
            audioSource = peerConnectionFactory!!.createAudioSource(MediaConstraints())
            val audioTrack = peerConnectionFactory!!.createAudioTrack("audio_track", audioSource!!)
            localStream!!.addTrack(audioTrack)
        }

        // Add local tracks
        localStream?.videoTracks?.forEach { track ->
            peerConnection!!.addTrack(track, listOf("local_stream"))
        }
        localStream?.audioTracks?.forEach { track ->
            peerConnection!!.addTrack(track, listOf("local_stream"))
        }

        // Create and send offer
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }

        peerConnection!!.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                if (sdp == null) return
                peerConnection!!.setLocalDescription(SimpleSdpObserver(), sdp)
                val payload = JSONObject().apply {
                    put("sdp", JSONObject().apply {
                        put("type", sdp.type.canonicalForm())
                        put("sdp",  sdp.description)
                    })
                    put("viewerSocketId", viewerSocketId)
                    put("deviceToken",    session.deviceToken)
                }
                socket?.emit("webrtc:offer", payload)
            }
        }, constraints)
    }

    // ─────────────────────────────────────────────────────────
    // Status & Location Loops
    // ─────────────────────────────────────────────────────────
    private fun startStatusLoop() {
        serviceScope.launch {
            while (isActive) {
                try {
                    val status = statusHelper.collect()
                    socket?.emit("status:update", JSONObject().apply {
                        put("batteryLevel",   status.batteryLevel)
                        put("isCharging",     status.isCharging)
                        put("networkType",    status.networkType)
                        put("wifiSsid",       status.wifiSsid)
                        put("signalStrength", status.signalStrength)
                        put("publicIp",       status.publicIp)
                        put("isScreenOn",     status.isScreenOn)
                    })
                } catch (e: Exception) {
                    Log.e(TAG, "Status push error", e)
                }
                delay(10_000L)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationLoop() {
        locationHelper.startUpdates(intervalMs = 10_000L) { lat, lng, acc, alt, speed, provider ->
            socket?.emit("location:update", JSONObject().apply {
                put("latitude",  lat)
                put("longitude", lng)
                put("accuracy",  acc)
                put("altitude",  alt)
                put("speed",     speed)
                put("provider",  provider)
            })
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = serviceScope.launch {
            delay(5_000L)
            connectSocket()
        }
    }

    // ─────────────────────────────────────────────────────────
    // Notification
    // ─────────────────────────────────────────────────────────
    private fun buildNotification(): Notification {
        val stopIntent  = Intent(this, MonitorService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, RemoteMonitorApp.CHANNEL_MONITOR)
            .setContentTitle("Remote Monitor Active")
            .setContentText("Your device is being monitored securely.")
            .setSmallIcon(R.drawable.ic_monitor_notification)
            .setOngoing(true)
            .addAction(R.drawable.ic_stop, "Stop", stopPending)
            .build()
    }

    // ─────────────────────────────────────────────────────────
    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        socket?.disconnect()
        socket?.off()
        peerConnection?.dispose()
        screenCapture?.stopCapture()
        screenCapture?.dispose()
        cameraCapture?.stopCapture()
        cameraCapture?.dispose()
        audioSource?.dispose()
        videoSource?.dispose()
        peerConnectionFactory?.dispose()
        eglBase?.release()
        locationHelper.stopUpdates()
        webRtcExecutor.shutdown()
    }

    /** Simple no-op SDP observer to reduce boilerplate. */
    open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) = Unit
        override fun onSetSuccess()                            = Unit
        override fun onCreateFailure(msg: String?)             = Unit
        override fun onSetFailure(msg: String?)                = Unit
    }
}
