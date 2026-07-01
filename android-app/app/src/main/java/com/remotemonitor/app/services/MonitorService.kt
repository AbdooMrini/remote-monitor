package com.remotemonitor.app.services

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.google.android.gms.location.Priority
import com.remotemonitor.app.R
import com.remotemonitor.app.RemoteMonitorApp
import com.remotemonitor.app.data.RetrofitClient
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.webrtc.*
import org.webrtc.CameraVideoCapturer.CameraFacing
import java.net.URI
import java.util.concurrent.Executors

/**
 * MonitorService — the core foreground service.
 *
 * Responsibilities:
 *  • Maintains WSS Socket.IO connection to the signaling server
 *  • Manages WebRTC PeerConnection for screen/camera/mic streaming
 *  • Sends GPS location every 10 seconds (configurable)
 *  • Pushes device status (battery, network, etc.) every 10 seconds
 *  • Auto-reconnects on network loss
 *  • Handles remote commands: flash, audio mute, camera control, location modes
 *  • Adaptive resource management based on viewer count
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
    private val offerMutex = Mutex()

    // ── Viewer count & adaptive management ────────────────────
    private var activeViewers = 0
    private var noViewerTimeoutJob: Job? = null

    // ── Location & status loop control ────────────────────────
    private var isHighFreqLocation = false
    private var locationIntervalMs = 10_000L
    private var statusIntervalMs   = 10_000L
    private var locationJob: Job? = null
    private var statusJob: Job? = null
    private val fusedClient by lazy { com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(this) }

    // ── Camera & flash state ──────────────────────────────────
    private var currentFacing = CameraFacing.FRONT
    private var isFlashOn = false

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

    // ═══════════════════════════════════════════════════════════
    //  COMMAND HANDLER — Remote commands from viewer
    // ═══════════════════════════════════════════════════════════

    private suspend fun handleCommand(command: String, payload: JSONObject): JSONObject {
        Log.d(TAG, "Handling command: $command, payload: $payload")
        return when (command) {
            "audio:mute" -> {
                val muted = payload.optBoolean("muted", true)
                toggleAudioMute(muted)
                JSONObject().apply { put("muted", muted) }
            }
            "flash:toggle" -> {
                val on = payload.optBoolean("on", !isFlashOn)
                toggleFlash(on)
                JSONObject().apply { put("on", isFlashOn) }
            }
            "camera:switch" -> {
                switchCamera()
                JSONObject().apply { put("facing", currentFacing.name.lowercase()) }
            }
            "camera:toggle" -> {
                val enabled = payload.optBoolean("enabled", true)
                toggleCamera(enabled)
                JSONObject().apply { put("enabled", enabled) }
            }
            "location:highfreq" -> {
                val enabled = payload.optBoolean("enabled", true)
                val interval = payload.optLong("intervalMs", 3_000L)
                isHighFreqLocation = enabled
                locationIntervalMs = if (enabled) interval else 10_000L
                restartLocationLoop()
                JSONObject().apply {
                    put("enabled", isHighFreqLocation)
                    put("intervalMs", locationIntervalMs)
                }
            }
            "location:single" -> {
                val highAccuracy = payload.optBoolean("highAccuracy", true)
                getSingleLocation(highAccuracy)
            }
            "status:now" -> {
                pushStatusNow()
                JSONObject().apply { put("pushed", true) }
            }
            else -> JSONObject().apply {
                put("status", "error")
                put("error", "Unknown command: $command")
            }
        }
    }

    // ── Flash LED control ────────────────────────────────────
    @SuppressLint("MissingPermission")
    private fun toggleFlash(on: Boolean) {
        try {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                facing == CameraCharacteristics.LENS_FACING_BACK
            } ?: run {
                Log.w(TAG, "No back camera found for flash control")
                return
            }

            cameraManager.setTorchMode(cameraId, on)
            isFlashOn = on
            Log.d(TAG, "Flash ${if (on) "ON" else "OFF"}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle flash: ${e.message}")
        }
    }

    // ── Remote audio mute ────────────────────────────────────
    private fun toggleAudioMute(muted: Boolean) {
        localStream?.audioTracks?.forEach { track ->
            track.setEnabled(!muted)
        }
        Log.d(TAG, "Audio ${if (muted) "MUTED" else "UNMUTED"}")
    }

    // ── Camera switch ────────────────────────────────────────
    private fun switchCamera() {
        try {
            // Stop current camera capture
            cameraCapture?.stopCapture()

            // Toggle facing
            currentFacing = if (currentFacing == CameraFacing.FRONT) CameraFacing.BACK else CameraFacing.FRONT

            // Dispose old capturer
            cameraCapture?.dispose()

            // Create new capturer with opposite facing
            val enumerator = Camera2Enumerator(this)
            val deviceNames = enumerator.deviceNames
            val targetFacing = if (currentFacing == CameraFacing.FRONT)
                android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT
            else
                android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK

            var newCapturer: VideoCapturer? = null
            for (deviceName in deviceNames) {
                val chars = enumerator.getCameraCharacteristics(deviceName)
                val facing = chars.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
                if (facing == targetFacing) {
                    newCapturer = enumerator.createCapturer(deviceName, null)
                    break
                }
            }

            if (newCapturer == null) {
                Log.w(TAG, "No camera found for facing: $currentFacing")
                return
            }

            cameraCapture = newCapturer

            // Create new video source and track
            val cameraVideoSource = peerConnectionFactory!!.createVideoSource(false)
            val cameraTrack = peerConnectionFactory!!.createVideoTrack("camera_track", cameraVideoSource)

            // Initialize new capturer
            val cameraSurfaceHelper = SurfaceTextureHelper.create("CameraThread", eglBase!!.eglBaseContext)
            cameraCapture!!.initialize(cameraSurfaceHelper, this.applicationContext, cameraVideoSource.capturerObserver)

            // Replace track in local stream
            val oldTracks = localStream?.videoTracks?.filter { it.id() == "camera_track" }
            oldTracks?.forEach { localStream!!.removeTrack(it) }
            localStream!!.addTrack(cameraTrack)

            // Replace sender in peer connection
            val senders = peerConnection?.senders
            val cameraSender = senders?.find { it.track()?.id() == "camera_track" }
            if (cameraSender != null) {
                cameraSender.setTrack(cameraTrack, false)
            } else {
                peerConnection?.addTrack(cameraTrack, listOf("local_stream"))
            }

            // Start capture if viewers are present
            if (activeViewers > 0) {
                cameraCapture!!.startCapture(1280, 720, 30)
            }

            // Trigger renegotiation
            triggerRenegotiation()

            Log.d(TAG, "Camera switched to $currentFacing")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to switch camera: ${e.message}")
        }
    }

    // ── Camera toggle ────────────────────────────────────────
    private fun toggleCamera(enabled: Boolean) {
        try {
            if (enabled) {
                cameraCapture?.startCapture(1280, 720, 30)
            } else {
                cameraCapture?.stopCapture()
            }
            Log.d(TAG, "Camera ${if (enabled) "ENABLED" else "DISABLED"}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle camera: ${e.message}")
        }
    }

    // ── Single location request ──────────────────────────────
    private suspend fun getSingleLocation(highAccuracy: Boolean): JSONObject =
        suspendCancellableCoroutine { cont ->
            try {
                val priority = if (highAccuracy)
                    Priority.PRIORITY_HIGH_ACCURACY
                else
                    Priority.PRIORITY_BALANCED_POWER_ACCURACY

                val request = com.google.android.gms.location.LocationRequest.Builder(priority, 1000)
                    .setWaitForAccurateLocation(highAccuracy)
                    .setMinUpdateIntervalMillis(500)
                    .setMaxUpdates(1)
                    .build()

                val callback = object : com.google.android.gms.location.LocationCallback() {
                    override fun onLocationResult(result: com.google.android.gms.location.LocationResult) {
                        result.lastLocation?.let { loc ->
                            cont.resume(JSONObject().apply {
                                put("lat", loc.latitude)
                                put("lng", loc.longitude)
                                put("accuracy", loc.accuracy.toDouble())
                                put("altitude", loc.altitude)
                                put("speed", loc.speed.toDouble())
                                put("provider", loc.provider ?: "fused")
                            })
                        } ?: cont.resume(JSONObject().apply {
                            put("status", "error")
                            put("error", "No location available")
                        })
                    }
                }

                fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper())

                cont.invokeOnCancellation {
                    fusedClient.removeLocationUpdates(callback)
                }
            } catch (e: Exception) {
                cont.resume(JSONObject().apply {
                    put("status", "error")
                    put("error", e.message)
                })
            }
        }

    // ── Restart location loop with new interval ──────────────
    private fun restartLocationLoop() {
        locationJob?.cancel()
        startLocationLoop()
    }

    // ── Push status immediately ──────────────────────────────
    private fun pushStatusNow() {
        serviceScope.launch {
            try {
                val status = statusHelper.collect()
                socket?.emit("status:update", JSONObject().apply {
                    put("batteryLevel", status.batteryLevel)
                    put("isCharging", status.isCharging)
                    put("networkType", status.networkType)
                    put("wifiSsid", status.wifiSsid)
                    put("signalStrength", status.signalStrength)
                    put("publicIp", status.publicIp)
                    put("isScreenOn", status.isScreenOn)
                })
            } catch (e: Exception) {
                Log.e(TAG, "Status push error", e)
            }
        }
    }

    // ── Ensure captures are running ──────────────────────────
    private fun ensureCapturesRunning() {
        noViewerTimeoutJob?.cancel()
        serviceScope.launch {
            try {
                if (screenCapture != null && activeViewers > 0) {
                    screenCapture!!.startCapture(
                        resources.displayMetrics.widthPixels.coerceAtMost(1920),
                        resources.displayMetrics.heightPixels.coerceAtMost(1080),
                        24
                    )
                }
                if (cameraCapture != null && activeViewers > 0) {
                    cameraCapture!!.startCapture(1280, 720, 24)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start captures: ${e.message}")
            }
        }
    }

    // ── Schedule capture stop when no viewers ────────────────
    private fun scheduleCaptureStop() {
        noViewerTimeoutJob?.cancel()
        noViewerTimeoutJob = serviceScope.launch {
            delay(10_000L)
            if (activeViewers == 0) {
                try {
                    screenCapture?.stopCapture()
                    cameraCapture?.stopCapture()
                    Log.d(TAG, "Captures stopped (no viewers)")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to stop captures: ${e.message}")
                }
            }
        }
    }

    // ── Trigger WebRTC renegotiation ─────────────────────────
    private fun triggerRenegotiation() {
        serviceScope.launch {
            try {
                val constraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
                }
                peerConnection?.createOffer(object : SimpleSdpObserver() {
                    override fun onCreateSuccess(sdp: SessionDescription?) {
                        if (sdp == null) return
                        peerConnection!!.setLocalDescription(SimpleSdpObserver(), sdp)
                        val payload = JSONObject().apply {
                            put("sdp", JSONObject().apply {
                                put("type", sdp.type.canonicalForm())
                                put("sdp", sdp.description)
                            })
                            put("deviceToken", session.deviceToken)
                        }
                        socket?.emit("webrtc:offer", payload)
                    }
                }, constraints)
            } catch (e: Exception) {
                Log.e(TAG, "Renegotiation failed: ${e.message}")
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  WebRTC Initialisation
    // ═══════════════════════════════════════════════════════════

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

            val surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase!!.eglBaseContext)
            screenCapture!!.initialize(surfaceTextureHelper, this.applicationContext, videoSource!!.capturerObserver)
        }

        // ── Camera track ──────────────────────────────
        cameraCapture = createCameraCapturer()
        if (cameraCapture != null) {
            val cameraVideoSource = peerConnectionFactory!!.createVideoSource(false)
            val cameraTrack = peerConnectionFactory!!.createVideoTrack("camera_track", cameraVideoSource)
            localStream!!.addTrack(cameraTrack)

            val cameraSurfaceHelper = SurfaceTextureHelper.create("CameraThread", eglBase!!.eglBaseContext)
            cameraCapture!!.initialize(cameraSurfaceHelper, this.applicationContext, cameraVideoSource.capturerObserver)
        }

        Log.d(TAG, "Local stream built (video tracks only)")
    }

    private fun createCameraCapturer(): VideoCapturer? {
        val enumerator = Camera2Enumerator(this)
        val deviceNames = enumerator.deviceNames

        // Try front facing camera first
        for (deviceName in deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                currentFacing = CameraFacing.FRONT
                return enumerator.createCapturer(deviceName, null)
            }
        }
        // Try back facing camera
        for (deviceName in deviceNames) {
            if (enumerator.isBackFacing(deviceName)) {
                currentFacing = CameraFacing.BACK
                return enumerator.createCapturer(deviceName, null)
            }
        }
        return null
    }

    // ═══════════════════════════════════════════════════════════
    //  Socket.IO Connection
    // ═══════════════════════════════════════════════════════════

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

        // ── Viewer count updates ──────────────────────────────
        s.on("viewer:count") { args ->
            val data = args.getOrNull(0) as? JSONObject ?: return@on
            val count = data.optInt("count", 0)
            activeViewers = count
            Log.d(TAG, "Viewer count updated: $activeViewers")

            if (activeViewers > 0) {
                ensureCapturesRunning()
            } else {
                scheduleCaptureStop()
            }
        }

        // ── Remote command handler ────────────────────────────
        s.on("viewer:device:command") { args ->
            val data = args.getOrNull(0) as? JSONObject ?: return@on
            val commandId = data.optString("commandId")
            val command = data.optString("command")
            val payload = data.optJSONObject("payload") ?: JSONObject()

            // Send ACK immediately
            s.emit("viewer:device:command:ack", JSONObject().apply {
                put("commandId", commandId)
                put("deviceToken", session.deviceToken)
                put("status", "received")
            })

            // Execute command asynchronously
            serviceScope.launch {
                val result = handleCommand(command, payload)
                s.emit("viewer:device:command:result", JSONObject().apply {
                    put("commandId", commandId)
                    put("deviceToken", session.deviceToken)
                    put("status", result.optString("status", "success"))
                    put("result", result)
                })
            }
        }

        // Viewer requests a WebRTC offer
        s.on("webrtc:request-offer") { args ->
            val viewerSocketId = (args.getOrNull(0) as? JSONObject)?.optString("viewerSocketId") ?: return@on
            serviceScope.launch { createOffer(viewerSocketId) }
        }

        // Legacy: viewer requests to switch camera
        s.on("webrtc:switch-camera") {
            handleCommand("camera:switch", JSONObject())
        }

        // Legacy: viewer requests to toggle camera
        s.on("webrtc:toggle-camera") { args ->
            val data = args.getOrNull(0) as? JSONObject ?: return@on
            val enabled = data.optBoolean("enabled", true)
            handleCommand("camera:toggle", JSONObject().apply { put("enabled", enabled) })
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

    // ═══════════════════════════════════════════════════════════
    //  WebRTC Offer / PeerConnection
    // ═══════════════════════════════════════════════════════════

    private suspend fun createOffer(viewerSocketId: String) = offerMutex.withLock {
        withContext(Dispatchers.IO) {
            val iceServers = listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
            )

            val config = PeerConnection.RTCConfiguration(iceServers).apply {
                sdpSemantics    = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            }

            // Check if we can reuse existing PeerConnection
            val pcState = peerConnection?.connectionState()
            if (pcState == PeerConnection.PeerConnectionState.CONNECTED ||
                pcState == PeerConnection.PeerConnectionState.CONNECTING) {
                Log.d(TAG, "Reusing existing PeerConnection (state: $pcState)")
                // Trigger renegotiation instead of recreating
                triggerRenegotiation()
                return@withContext
            }

            // Dispose old connection if it exists
            if (peerConnection != null) {
                Log.d(TAG, "Disposing old PeerConnection (state: $pcState)")
                peerConnection!!.dispose()
            }

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

            // Start captures if viewers are present
            if (activeViewers > 0) {
                ensureCapturesRunning()
            }

            // Initialize audio source on demand if needed
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
    }

    // ═══════════════════════════════════════════════════════════
    //  Status & Location Loops
    // ═══════════════════════════════════════════════════════════

    private fun startStatusLoop() {
        statusJob?.cancel()
        statusJob = serviceScope.launch {
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
                delay(statusIntervalMs)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationLoop() {
        locationJob?.cancel()
        val priority = if (isHighFreqLocation) Priority.PRIORITY_HIGH_ACCURACY
                      else Priority.PRIORITY_BALANCED_POWER_ACCURACY

        locationHelper.startUpdates(
            intervalMs = locationIntervalMs,
            priority = priority
        ) { lat, lng, acc, alt, speed, provider ->
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

    // ═══════════════════════════════════════════════════════════
    //  Notification
    // ═══════════════════════════════════════════════════════════

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

    // ═══════════════════════════════════════════════════════════
    //  Lifecycle
    // ═══════════════════════════════════════════════════════════

    override fun onDestroy() {
        super.onDestroy()
        noViewerTimeoutJob?.cancel()
        locationJob?.cancel()
        statusJob?.cancel()
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