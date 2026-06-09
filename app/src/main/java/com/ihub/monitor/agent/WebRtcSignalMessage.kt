package com.ihub.monitor.agent

data class WebRtcSignalMessage(
    val type: String,
    val deviceId: String? = null,
    val viewerId: String? = null,
    val viewerName: String? = null,
    val targetRole: String? = null,
    val sdpType: String? = null,
    val sdp: String? = null,
    val candidate: String? = null,
    val sdpMid: String? = null,
    val sdpMLineIndex: Int? = null,
    val status: String? = null,
    val errorMessage: String? = null
)
