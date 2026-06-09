package com.ihub.monitor.agent

import android.util.Log
import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

class RemoteWebSocketClient(
    private val websocketUrl: String,
    private val deviceId: String,
    private val deviceName: String,
    private val agentVersion: String,
    private val callback: Callback
) {

    interface Callback {
        fun onConnected()
        fun onDisconnected()
        fun onRemoteMessage(message: RemoteMessage)
    }

    private val gson = Gson()
    private val httpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    @Volatile
    private var webSocket: WebSocket? = null

    fun connect() {
        val request = Request.Builder()
            .url(websocketUrl)
            .build()

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                send(
                    RemoteMessage(
                        type = "DEVICE_REGISTER",
                        deviceId = deviceId,
                        deviceName = deviceName,
                        agentVersion = agentVersion
                    )
                )
                callback.onConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = gson.fromJson(text, RemoteMessage::class.java)
                callback.onRemoteMessage(message)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                callback.onDisconnected()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("RemoteWebSocket", "WebSocket failure", t)
                callback.onDisconnected()
            }
        })
    }

    fun disconnect() {
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
    }

    fun sendScreenFrame(width: Int, height: Int, timestamp: Long, imageBase64: String) {
        send(
            RemoteMessage(
                type = "SCREEN_FRAME",
                deviceId = deviceId,
                width = width,
                height = height,
                timestamp = timestamp,
                imageBase64 = imageBase64
            )
        )
    }

    fun sendCommandResult(requestId: String?, status: String, errorMessage: String? = null) {
        send(
            RemoteMessage(
                type = "COMMAND_RESULT",
                requestId = requestId,
                deviceId = deviceId,
                status = status,
                errorMessage = errorMessage
            )
        )
    }

    fun sendSupportRequest() {
        send(
            RemoteMessage(
                type = "SUPPORT_REQUEST",
                deviceId = deviceId,
                deviceName = deviceName
            )
        )
    }

    fun sendControlApproved(viewerId: String?, viewerName: String?) {
        send(
            RemoteMessage(
                type = "CONTROL_APPROVED",
                deviceId = deviceId,
                viewerId = viewerId,
                viewerName = viewerName
            )
        )
    }

    fun sendControlRejected(viewerId: String?, viewerName: String?) {
        send(
            RemoteMessage(
                type = "CONTROL_REJECTED",
                deviceId = deviceId,
                viewerId = viewerId,
                viewerName = viewerName
            )
        )
    }

    private fun send(message: RemoteMessage) {
        webSocket?.send(gson.toJson(message))
    }
}
