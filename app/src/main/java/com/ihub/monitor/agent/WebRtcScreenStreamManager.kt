package com.ihub.monitor.agent

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.media.projection.MediaProjection
import android.util.Log
import android.util.DisplayMetrics
import android.view.WindowManager
import com.google.gson.Gson
import org.webrtc.CandidatePairChangeEvent
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

class WebRtcScreenStreamManager(
    private val context: Context,
    private val signalingUrl: String,
    private val deviceId: String,
    private val listener: Listener
) : WebRtcScreenSignalingClient.Callback {

    interface Listener {
        fun getProjectionPermissionData(): Intent?
        fun onScreenStreamStatusChanged(status: String)
        fun onScreenControlMessage(message: RemoteMessage)
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val gson = Gson()
    private val signalingClient = WebRtcScreenSignalingClient(signalingUrl, deviceId, this)
    private val eglBase by lazy { EglBase.create() }

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var screenCapturer: ScreenCapturerAndroid? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var controlDataChannel: DataChannel? = null
    private var captureWidth = DEFAULT_CAPTURE_WIDTH
    private var captureHeight = DEFAULT_CAPTURE_HEIGHT
    private var streamActive = false

    fun connect() {
        initializeFactoryIfNeeded()
        signalingClient.connect()
    }

    fun disconnect() {
        signalingClient.disconnect()
        closePeerConnection(notifyState = false)
        executor.shutdownNow()
    }

    override fun onConnected() {
        Log.i("WebRtcScreen", "Signaling connected for device=$deviceId")
    }

    override fun onDisconnected() {
        Log.w("WebRtcScreen", "Signaling disconnected for device=$deviceId")
        closePeerConnection(notifyState = true)
    }

    override fun onSignal(message: WebRtcSignalMessage) {
        executor.execute {
            try {
                when (message.type) {
                    "REGISTER_ACK" -> {
                        Log.i("WebRtcScreen", "Device registered for screen stream")
                        listener.onScreenStreamStatusChanged("WebRTC screen đã sẵn sàng")
                    }
                    "SCREEN_STREAM_REQUEST" -> handleScreenStreamRequest(message)
                    "SCREEN_ANSWER" -> handleAnswer(message)
                    "SCREEN_ICE_CANDIDATE" -> handleIceCandidate(message)
                    "SCREEN_STREAM_STOP", "DEVICE_OFFLINE" -> {
                        Log.i("WebRtcScreen", "Stopping screen stream due to remote signal type=${message.type}")
                        closePeerConnection(notifyState = true)
                    }
                    "ERROR" -> {
                        Log.w("WebRtcScreen", "Signaling error: ${message.errorMessage}")
                        listener.onScreenStreamStatusChanged(message.errorMessage ?: "WebRTC screen signaling gặp lỗi")
                    }
                }
            } catch (exception: Exception) {
                Log.e("WebRtcScreen", "Failed to handle signal", exception)
                signalingClient.send(
                    WebRtcSignalMessage(
                        type = "SCREEN_STREAM_REJECTED",
                        deviceId = deviceId,
                        errorMessage = exception.message ?: "Failed to handle WebRTC screen signal"
                    )
                )
                closePeerConnection(notifyState = true)
            }
        }
    }

    private fun handleScreenStreamRequest(message: WebRtcSignalMessage) {
        if (streamActive || peerConnection != null) {
            Log.w("WebRtcScreen", "Rejecting SCREEN_STREAM_REQUEST because a stream is already active")
            signalingClient.send(
                WebRtcSignalMessage(
                    type = "SCREEN_STREAM_REJECTED",
                    deviceId = deviceId,
                    errorMessage = "Thiết bị đang stream màn hình"
                )
            )
            return
        }

        val projectionPermissionData = listener.getProjectionPermissionData()
        if (projectionPermissionData == null) {
            Log.w("WebRtcScreen", "Rejecting SCREEN_STREAM_REQUEST because projection permission is missing")
            signalingClient.send(
                WebRtcSignalMessage(
                    type = "SCREEN_STREAM_REJECTED",
                    deviceId = deviceId,
                    errorMessage = "Thiết bị chưa có quyền chia sẻ màn hình"
                )
            )
            return
        }

        listener.onScreenStreamStatusChanged("Admin đã yêu cầu WebRTC screen, đang khởi tạo stream")
        signalingClient.send(
            WebRtcSignalMessage(
                type = "SCREEN_STREAM_ACCEPTED",
                deviceId = deviceId
            )
        )

        val currentPeerConnection = ensurePeerConnection(projectionPermissionData)
        val constraints = MediaConstraints()
        currentPeerConnection.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sessionDescription: SessionDescription?) {
                if (sessionDescription == null) {
                    Log.w("WebRtcScreen", "createOffer returned null SessionDescription")
                    return
                }

                Log.i(
                    "WebRtcScreen",
                    "Created SCREEN_OFFER type=${sessionDescription.type.canonicalForm()} sdpLength=${sessionDescription.description.length}"
                )
                currentPeerConnection.setLocalDescription(noopSdpObserver("setLocalOffer"), sessionDescription)
                signalingClient.send(
                    WebRtcSignalMessage(
                        type = "SCREEN_OFFER",
                        deviceId = deviceId,
                        sdpType = sessionDescription.type.canonicalForm(),
                        sdp = sessionDescription.description
                    )
                )
            }

            override fun onSetSuccess() = Unit

            override fun onCreateFailure(error: String?) {
                Log.e("WebRtcScreen", "createOffer failed: $error")
                listener.onScreenStreamStatusChanged("Không thể tạo WebRTC offer cho màn hình")
                closePeerConnection(notifyState = true)
            }

            override fun onSetFailure(error: String?) {
                Log.e("WebRtcScreen", "setLocalOffer failed: $error")
            }
        }, constraints)
    }

    private fun handleAnswer(message: WebRtcSignalMessage) {
        val currentPeerConnection = peerConnection ?: return
        if (message.sdp.isNullOrBlank() || message.sdpType.isNullOrBlank()) {
            Log.w("WebRtcScreen", "Ignoring SCREEN_ANSWER because SDP is missing")
            return
        }

        Log.i("WebRtcScreen", "Received SCREEN_ANSWER sdpType=${message.sdpType} sdpLength=${message.sdp.length}")
        val remoteDescription = SessionDescription(SessionDescription.Type.fromCanonicalForm(message.sdpType), message.sdp)
        currentPeerConnection.setRemoteDescription(noopSdpObserver("setRemoteAnswer"), remoteDescription)
    }

    private fun handleIceCandidate(message: WebRtcSignalMessage) {
        val currentPeerConnection = peerConnection ?: return
        val candidate = message.candidate ?: return
        Log.i(
            "WebRtcScreen",
            "Received SCREEN_ICE_CANDIDATE sdpMid=${message.sdpMid} sdpMLineIndex=${message.sdpMLineIndex}"
        )
        currentPeerConnection.addIceCandidate(
            IceCandidate(message.sdpMid, message.sdpMLineIndex ?: 0, candidate)
        )
    }

    private fun initializeFactoryIfNeeded() {
        if (peerConnectionFactory != null) {
            return
        }

        Log.i("WebRtcScreen", "Initializing PeerConnectionFactory")
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(PeerConnectionFactory.Options())
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
    }

    private fun ensurePeerConnection(projectionPermissionData: Intent): PeerConnection {
        peerConnection?.let { return it }

        initializeLocalVideoTrack(projectionPermissionData)

        val rtcConfig = PeerConnection.RTCConfiguration(emptyList())
        val connection = requireNotNull(peerConnectionFactory).createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onSignalingChange(newState: PeerConnection.SignalingState?) = Unit
                override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) = Unit
                override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
                override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) = Unit

                override fun onIceCandidate(candidate: IceCandidate?) {
                    if (candidate == null) {
                        Log.i("WebRtcScreen", "ICE gathering completed")
                        return
                    }

                    Log.i(
                        "WebRtcScreen",
                        "Sending SCREEN_ICE_CANDIDATE sdpMid=${candidate.sdpMid} sdpMLineIndex=${candidate.sdpMLineIndex}"
                    )
                    signalingClient.send(
                        WebRtcSignalMessage(
                            type = "SCREEN_ICE_CANDIDATE",
                            deviceId = deviceId,
                            candidate = candidate.sdp,
                            sdpMid = candidate.sdpMid,
                            sdpMLineIndex = candidate.sdpMLineIndex
                        )
                    )
                }

                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
                override fun onAddStream(stream: org.webrtc.MediaStream?) = Unit
                override fun onRemoveStream(stream: org.webrtc.MediaStream?) = Unit
                override fun onDataChannel(dataChannel: org.webrtc.DataChannel?) = Unit
                override fun onRenegotiationNeeded() = Unit
                override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out org.webrtc.MediaStream>?) = Unit
                override fun onTrack(transceiver: RtpTransceiver?) = Unit

                override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                    Log.i("WebRtcScreen", "Peer connection state=$newState")
                    when (newState) {
                        PeerConnection.PeerConnectionState.CONNECTED -> {
                            streamActive = true
                            listener.onScreenStreamStatusChanged("WebRTC screen đang hoạt động")
                        }
                        PeerConnection.PeerConnectionState.FAILED,
                        PeerConnection.PeerConnectionState.DISCONNECTED,
                        PeerConnection.PeerConnectionState.CLOSED -> {
                            closePeerConnection(notifyState = true)
                        }
                        else -> Unit
                    }
                }

                override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent?) = Unit
                override fun onStandardizedIceConnectionChange(newState: PeerConnection.IceConnectionState?) = Unit
            }
        ) ?: error("Failed to create PeerConnection")

        val dataChannel = connection.createDataChannel("control", DataChannel.Init())
        registerControlDataChannel(dataChannel)
        val track = requireNotNull(localVideoTrack) { "Local video track must be initialized" }
        connection.addTrack(track, listOf("screen-stream"))
        peerConnection = connection
        return connection
    }

    private fun initializeLocalVideoTrack(projectionPermissionData: Intent) {
        if (localVideoTrack != null) {
            return
        }

        val captureSize = resolveCaptureSize()
        captureWidth = captureSize.first
        captureHeight = captureSize.second
        Log.i("WebRtcScreen", "Initializing local screen capturer")
        Log.i("WebRtcScreen", "Screen capture target size=${captureWidth}x${captureHeight}")
        val capturer = ScreenCapturerAndroid(
            projectionPermissionData,
            object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.w("WebRtcScreen", "MediaProjection stopped by system during WebRTC screen capture")
                    signalingClient.send(
                        WebRtcSignalMessage(
                            type = "SCREEN_STREAM_STOP",
                            deviceId = deviceId,
                            errorMessage = "MediaProjection đã bị dừng trên thiết bị"
                        )
                    )
                    closePeerConnection(notifyState = true)
                }
            }
        )

        val helper = SurfaceTextureHelper.create("WebRtcScreenCaptureThread", eglBase.eglBaseContext)
        val source = requireNotNull(peerConnectionFactory).createVideoSource(true)
        capturer.initialize(helper, context, source.capturerObserver)
        capturer.startCapture(captureWidth, captureHeight, CAPTURE_FPS)

        val track = requireNotNull(peerConnectionFactory).createVideoTrack("SCREEN_VIDEO_TRACK", source)

        screenCapturer = capturer
        surfaceTextureHelper = helper
        videoSource = source
        localVideoTrack = track
    }

    private fun closePeerConnection(notifyState: Boolean) {
        if (peerConnection == null && localVideoTrack == null && screenCapturer == null && !streamActive) {
            if (notifyState) {
                listener.onScreenStreamStatusChanged("WebRTC screen đã dừng")
            }
            return
        }

        try {
            screenCapturer?.stopCapture()
        } catch (exception: Exception) {
            Log.w("WebRtcScreen", "stopCapture failed", exception)
        }

        controlDataChannel?.unregisterObserver()
        controlDataChannel?.close()
        controlDataChannel = null

        peerConnection?.close()
        peerConnection = null

        localVideoTrack?.dispose()
        localVideoTrack = null

        videoSource?.dispose()
        videoSource = null

        screenCapturer?.dispose()
        screenCapturer = null

        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null

        streamActive = false
        if (notifyState) {
            listener.onScreenStreamStatusChanged("WebRTC screen đã dừng")
        }
    }

    private fun registerControlDataChannel(dataChannel: DataChannel) {
        controlDataChannel?.unregisterObserver()
        controlDataChannel?.close()
        controlDataChannel = dataChannel

        dataChannel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit

            override fun onStateChange() {
                Log.i("WebRtcScreen", "Control data channel state=${dataChannel.state()}")
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                if (buffer.binary) {
                    Log.w("WebRtcScreen", "Ignoring binary control message on data channel")
                    return
                }

                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                val payload = String(bytes, StandardCharsets.UTF_8)
                Log.i("WebRtcScreen", "Received control message via data channel: $payload")
                runCatching {
                    gson.fromJson(payload, RemoteMessage::class.java)
                }.onSuccess { message ->
                    listener.onScreenControlMessage(message)
                }.onFailure { exception ->
                    Log.e("WebRtcScreen", "Failed to parse control message from data channel", exception)
                }
            }
        })
    }

    private fun resolveCaptureSize(): Pair<Int, Int> {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val rawWidth: Int
        val rawHeight: Int

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val bounds: Rect = windowManager.currentWindowMetrics.bounds
            rawWidth = bounds.width()
            rawHeight = bounds.height()
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            rawWidth = metrics.widthPixels
            rawHeight = metrics.heightPixels
        }

        if (rawWidth <= 0 || rawHeight <= 0) {
            return DEFAULT_CAPTURE_WIDTH to DEFAULT_CAPTURE_HEIGHT
        }

        val longEdge = maxOf(rawWidth, rawHeight).toFloat()
        val scale = TARGET_LONG_EDGE.toFloat() / longEdge
        val scaledWidth = (rawWidth * scale).toInt().coerceAtLeast(MIN_CAPTURE_EDGE)
        val scaledHeight = (rawHeight * scale).toInt().coerceAtLeast(MIN_CAPTURE_EDGE)

        val evenWidth = if (scaledWidth % 2 == 0) scaledWidth else scaledWidth - 1
        val evenHeight = if (scaledHeight % 2 == 0) scaledHeight else scaledHeight - 1

        return evenWidth.coerceAtLeast(MIN_CAPTURE_EDGE) to evenHeight.coerceAtLeast(MIN_CAPTURE_EDGE)
    }

    private fun noopSdpObserver(tag: String): SdpObserver = object : SdpObserver {
        override fun onCreateSuccess(sessionDescription: SessionDescription?) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) {
            Log.e("WebRtcScreen", "$tag create failure: $error")
        }

        override fun onSetFailure(error: String?) {
            Log.e("WebRtcScreen", "$tag set failure: $error")
        }
    }

    companion object {
        private const val DEFAULT_CAPTURE_WIDTH = 720
        private const val DEFAULT_CAPTURE_HEIGHT = 1280
        private const val TARGET_LONG_EDGE = 1280
        private const val MIN_CAPTURE_EDGE = 360
        private const val CAPTURE_FPS = 12
    }
}
