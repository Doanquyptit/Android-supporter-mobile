package com.ihub.monitor.agent

import android.util.Log
import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

class WebRtcSignalingClient(
    private val websocketUrl: String,
    private val deviceId: String,
    private val callback: Callback
) {

    interface Callback {
        fun onConnected()
        fun onDisconnected()
        fun onSignal(message: WebRtcSignalMessage)
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
                callback.onConnected()
                send(
                    WebRtcSignalMessage(
                        type = "REGISTER_DEVICE",
                        deviceId = deviceId
                    )
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                callback.onSignal(gson.fromJson(text, WebRtcSignalMessage::class.java))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                callback.onDisconnected()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("WebRtcSignaling", "WebSocket failure", t)
                callback.onDisconnected()
            }
        })
    }

    fun disconnect() {
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
    }

    fun send(message: WebRtcSignalMessage) {
        webSocket?.send(gson.toJson(message))
    }
}
