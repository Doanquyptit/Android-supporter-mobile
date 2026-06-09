package com.ihub.monitor.agent

import android.content.Context
import android.media.AudioManager
import android.util.Log
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.util.concurrent.Executors

class WebRtcAudioCallManager(
    private val context: Context,
    private val signalingUrl: String,
    private val deviceId: String,
    private val listener: Listener
) : WebRtcSignalingClient.Callback {

    interface Listener {
        fun onIncomingCall(viewerName: String?)
        fun onIncomingCallCancelled()
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val signalingClient = WebRtcSignalingClient(signalingUrl, deviceId, this)
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localAudioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var lastRemoteOfferSdp: String? = null
    private var incomingCallPending = false
    private var acceptedCallPendingOffer = false
    private val eglBase by lazy { EglBase.create() }

    fun connect() {
        initializeFactoryIfNeeded()
        signalingClient.connect()
    }

    fun disconnect() {
        signalingClient.disconnect()
        closePeerConnection()
        executor.shutdownNow()
    }

    override fun onConnected() {
        Log.i("WebRtcAudioCall", "Signaling connected for device=$deviceId")
    }

    override fun onDisconnected() {
        Log.w("WebRtcAudioCall", "Signaling disconnected for device=$deviceId")
        closePeerConnection()
    }

    override fun onSignal(message: WebRtcSignalMessage) {
        executor.execute {
            try {
                when (message.type) {
                    "REGISTER_ACK" -> Log.i("WebRtcAudioCall", "Device registered for audio call")
                    "CALL_REQUEST" -> handleCallRequest(message)
                    "CALL_ACCEPTED" -> Log.i("WebRtcAudioCall", "CALL_ACCEPTED received on device side - ignoring")
                    "OFFER" -> handleOffer(message)
                    "ANSWER" -> handleAnswer(message)
                    "ICE_CANDIDATE" -> handleIceCandidate(message)
                    "HANGUP", "DEVICE_OFFLINE" -> {
                        listener.onIncomingCallCancelled()
                        closePeerConnection()
                    }
                    "ERROR" -> Log.w("WebRtcAudioCall", "Signaling error: ${message.errorMessage}")
                }
            } catch (exception: Exception) {
                Log.e("WebRtcAudioCall", "Failed to handle signal", exception)
                signalingClient.send(
                    WebRtcSignalMessage(
                        type = "CALL_REJECTED",
                        deviceId = deviceId,
                        errorMessage = exception.message ?: "Failed to handle WebRTC signal"
                    )
                )
            }
        }
    }

    fun acceptIncomingCall() {
        executor.execute {
            if (!incomingCallPending) {
                Log.w("WebRtcAudioCall", "acceptIncomingCall ignored because there is no pending incoming call")
                return@execute
            }

            incomingCallPending = false
            acceptedCallPendingOffer = true
            signalingClient.send(
                WebRtcSignalMessage(
                    type = "CALL_ACCEPTED",
                    deviceId = deviceId
                )
            )
            Log.i("WebRtcAudioCall", "Incoming call accepted by device user")
        }
    }

    fun rejectIncomingCall(reason: String = "Thiết bị từ chối cuộc gọi") {
        executor.execute {
            if (!incomingCallPending) {
                Log.w("WebRtcAudioCall", "rejectIncomingCall ignored because there is no pending incoming call")
                return@execute
            }

            incomingCallPending = false
            acceptedCallPendingOffer = false
            signalingClient.send(
                WebRtcSignalMessage(
                    type = "CALL_REJECTED",
                    deviceId = deviceId,
                    errorMessage = reason
                )
            )
            listener.onIncomingCallCancelled()
            Log.i("WebRtcAudioCall", "Incoming call rejected by device user")
        }
    }

    private fun handleCallRequest(message: WebRtcSignalMessage) {
        if (incomingCallPending || acceptedCallPendingOffer || peerConnection != null) {
            Log.w("WebRtcAudioCall", "Rejecting CALL_REQUEST because audio call is already busy")
            signalingClient.send(
                WebRtcSignalMessage(
                    type = "CALL_REJECTED",
                    deviceId = deviceId,
                    errorMessage = "Thiết bị đang bận"
                )
            )
            return
        }

        incomingCallPending = true
        acceptedCallPendingOffer = false
        Log.i("WebRtcAudioCall", "Incoming CALL_REQUEST from viewer=${message.viewerName ?: message.viewerId}")
        listener.onIncomingCall(message.viewerName ?: message.viewerId)
    }

    private fun handleOffer(message: WebRtcSignalMessage) {
        if (message.sdp.isNullOrBlank() || message.sdpType.isNullOrBlank()) {
            Log.w("WebRtcAudioCall", "Ignoring OFFER because SDP is missing")
            return
        }

        if (!acceptedCallPendingOffer && peerConnection == null) {
            Log.w("WebRtcAudioCall", "Rejecting OFFER because call has not been accepted on device")
            signalingClient.send(
                WebRtcSignalMessage(
                    type = "CALL_REJECTED",
                    deviceId = deviceId,
                    errorMessage = "Thiết bị chưa chấp nhận cuộc gọi"
                )
            )
            return
        }

        if (message.sdp == lastRemoteOfferSdp) {
            Log.w("WebRtcAudioCall", "Ignoring duplicate OFFER with identical SDP")
            return
        }

        Log.i("WebRtcAudioCall", "Received OFFER, sdpType=${message.sdpType}, sdpLength=${message.sdp.length}")
        val currentPeerConnection = ensurePeerConnection()
        setCommunicationAudioMode()
        val remoteDescription = SessionDescription(SessionDescription.Type.fromCanonicalForm(message.sdpType), message.sdp)
        currentPeerConnection.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                acceptedCallPendingOffer = false
                lastRemoteOfferSdp = message.sdp
                currentPeerConnection.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(sessionDescription: SessionDescription?) {
                        if (sessionDescription == null) {
                            Log.w("WebRtcAudioCall", "createAnswer returned null SessionDescription")
                            return
                        }

                        Log.i("WebRtcAudioCall", "Created ANSWER, type=${sessionDescription.type.canonicalForm()}, sdpLength=${sessionDescription.description.length}")
                        currentPeerConnection.setLocalDescription(noopSdpObserver("setLocalAnswer"), sessionDescription)
                        signalingClient.send(
                            WebRtcSignalMessage(
                                type = "ANSWER",
                                deviceId = deviceId,
                                sdpType = sessionDescription.type.canonicalForm(),
                                sdp = sessionDescription.description
                            )
                        )
                    }

                    override fun onSetSuccess() = Unit
                    override fun onCreateFailure(error: String?) {
                        Log.e("WebRtcAudioCall", "createAnswer failed: $error")
                    }

                    override fun onSetFailure(error: String?) {
                        Log.e("WebRtcAudioCall", "setLocalAnswer failed: $error")
                    }
                }, MediaConstraints())
            }

            override fun onCreateSuccess(sessionDescription: SessionDescription?) = Unit
            override fun onCreateFailure(error: String?) = Unit
            override fun onSetFailure(error: String?) {
                Log.e("WebRtcAudioCall", "setRemoteOffer failed: $error")
            }
        }, remoteDescription)
    }

    private fun handleAnswer(message: WebRtcSignalMessage) {
        val currentPeerConnection = peerConnection ?: return
        if (message.sdp.isNullOrBlank() || message.sdpType.isNullOrBlank()) {
            Log.w("WebRtcAudioCall", "Ignoring ANSWER because SDP is missing")
            return
        }

        Log.i("WebRtcAudioCall", "Received ANSWER, sdpType=${message.sdpType}, sdpLength=${message.sdp.length}")
        val remoteDescription = SessionDescription(SessionDescription.Type.fromCanonicalForm(message.sdpType), message.sdp)
        currentPeerConnection.setRemoteDescription(noopSdpObserver("setRemoteAnswer"), remoteDescription)
    }

    private fun handleIceCandidate(message: WebRtcSignalMessage) {
        val currentPeerConnection = peerConnection ?: return
        val candidate = message.candidate ?: return
        Log.i(
            "WebRtcAudioCall",
            "Received ICE candidate, sdpMid=${message.sdpMid}, sdpMLineIndex=${message.sdpMLineIndex}"
        )
        currentPeerConnection.addIceCandidate(
            IceCandidate(message.sdpMid, message.sdpMLineIndex ?: 0, candidate)
        )
    }

    private fun initializeFactoryIfNeeded() {
        if (peerConnectionFactory != null) {
            return
        }

        Log.i("WebRtcAudioCall", "Initializing PeerConnectionFactory")
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )

        val options = PeerConnectionFactory.Options()
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
    }

    private fun ensurePeerConnection(): PeerConnection {
        peerConnection?.let { return it }

        Log.i("WebRtcAudioCall", "Creating new PeerConnection for device=$deviceId")
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
                        Log.i("WebRtcAudioCall", "ICE gathering completed")
                        return
                    }

                    Log.i(
                        "WebRtcAudioCall",
                        "Sending ICE candidate, sdpMid=${candidate.sdpMid}, sdpMLineIndex=${candidate.sdpMLineIndex}"
                    )
                    signalingClient.send(
                        WebRtcSignalMessage(
                            type = "ICE_CANDIDATE",
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
                override fun onAddTrack(receiver: org.webrtc.RtpReceiver?, mediaStreams: Array<out org.webrtc.MediaStream>?) = Unit
                override fun onTrack(transceiver: org.webrtc.RtpTransceiver?) = Unit
                override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                    Log.i("WebRtcAudioCall", "Peer connection state=$newState")
                    if (newState == PeerConnection.PeerConnectionState.FAILED ||
                        newState == PeerConnection.PeerConnectionState.DISCONNECTED ||
                        newState == PeerConnection.PeerConnectionState.CLOSED
                    ) {
                        resetAudioMode()
                    }
                }

                override fun onSelectedCandidatePairChanged(event: org.webrtc.CandidatePairChangeEvent?) = Unit
                override fun onStandardizedIceConnectionChange(newState: PeerConnection.IceConnectionState?) = Unit
            }
        ) ?: error("Failed to create PeerConnection")

        val constraints = MediaConstraints()
        Log.i("WebRtcAudioCall", "Creating local audio source and track")
        localAudioSource = requireNotNull(peerConnectionFactory).createAudioSource(constraints)
        localAudioTrack = requireNotNull(peerConnectionFactory).createAudioTrack("audio_track", localAudioSource)
        connection.addTrack(localAudioTrack, listOf("ihub_audio_stream"))
        peerConnection = connection
        return connection
    }

    private fun closePeerConnection() {
        Log.i("WebRtcAudioCall", "Closing PeerConnection")
        peerConnection?.close()
        peerConnection = null
        lastRemoteOfferSdp = null
        incomingCallPending = false
        acceptedCallPendingOffer = false
        localAudioTrack = null
        localAudioSource?.dispose()
        localAudioSource = null
        resetAudioMode()
    }

    private fun setCommunicationAudioMode() {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = true
        audioManager.isMicrophoneMute = false
    }

    private fun resetAudioMode() {
        audioManager.mode = AudioManager.MODE_NORMAL
        audioManager.isSpeakerphoneOn = false
    }

    private fun noopSdpObserver(tag: String) = object : SdpObserver {
        override fun onCreateSuccess(sessionDescription: SessionDescription?) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) {
            Log.e("WebRtcAudioCall", "$tag create failure: $error")
        }

        override fun onSetFailure(error: String?) {
            Log.e("WebRtcAudioCall", "$tag set failure: $error")
        }
    }
}
