package com.ihub.monitor.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class ScreenCaptureService : Service(), RemoteWebSocketClient.Callback, WebRtcAudioCallManager.Listener {

    private val frameHandler = Handler(Looper.getMainLooper())

    private var imageReader: ImageReader? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var webSocketClient: RemoteWebSocketClient? = null
    private var webRtcAudioCallManager: WebRtcAudioCallManager? = null
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0
    private var streamedFrameWidth = 0
    private var streamedFrameHeight = 0
    private var activeDeviceId = ""
    private var activeDeviceName = ""
    private var projectionStarted = false
    private var mediaProjectionCallback: MediaProjection.Callback? = null
    private var pendingControlViewerId: String? = null
    private var pendingControlViewerName: String? = null
    private var pendingSupportRequestOnConnect = false
    private var baseForegroundStarted = false
    private var storedProjectionResultCode = 0
    private var storedProjectionData: Intent? = null

    private val frameRunnable = object : Runnable {
        override fun run() {
            sendLatestFrame()
            frameHandler.postDelayed(this, FRAME_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_REQUEST_SUPPORT -> {
                webSocketClient?.sendSupportRequest()
                updateNotification("Đã gửi yêu cầu hỗ trợ tới Admin Web")
                return START_STICKY
            }
            ACTION_APPROVE_CONTROL -> {
                webSocketClient?.sendControlApproved(pendingControlViewerId, pendingControlViewerName)
                cancelIncomingControlNotification()
                pendingControlViewerId = null
                pendingControlViewerName = null
                updateNotification("Khách hàng đã đồng ý quyền điều khiển từ xa")
                return START_STICKY
            }
            ACTION_REJECT_CONTROL -> {
                webSocketClient?.sendControlRejected(pendingControlViewerId, pendingControlViewerName)
                cancelIncomingControlNotification()
                pendingControlViewerId = null
                pendingControlViewerName = null
                updateNotification("Khách hàng đã từ chối quyền điều khiển từ xa")
                return START_STICKY
            }
            ACTION_ACCEPT_CALL -> {
                webRtcAudioCallManager?.acceptIncomingCall()
                cancelIncomingCallNotification()
                updateNotification("Đã chấp nhận cuộc gọi, đang thiết lập audio")
                return START_STICKY
            }
            ACTION_REJECT_CALL -> {
                webRtcAudioCallManager?.rejectIncomingCall()
                cancelIncomingCallNotification()
                updateNotification("Đã từ chối cuộc gọi đến")
                return START_STICKY
            }
        }

        val websocketUrl = intent?.getStringExtra(EXTRA_WEBSOCKET_URL)
        val deviceId = intent?.getStringExtra(EXTRA_DEVICE_ID)
        val deviceName = intent?.getStringExtra(EXTRA_DEVICE_NAME)
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val projectionData = intent?.getParcelableExtra<Intent>(EXTRA_PROJECTION_DATA)
        pendingSupportRequestOnConnect = intent?.getBooleanExtra(EXTRA_SUPPORT_REQUEST_ON_CONNECT, false) ?: pendingSupportRequestOnConnect

        if (websocketUrl.isNullOrBlank() || deviceId.isNullOrBlank() || deviceName.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }

        activeDeviceId = deviceId
        activeDeviceName = deviceName

        if (!baseForegroundStarted) {
            startBaseForeground()
        }

        if (projectionData != null) {
            storedProjectionResultCode = resultCode
            storedProjectionData = projectionData
        }

        if (webSocketClient == null) {
            initializeWebSocket(websocketUrl, deviceId, deviceName)
        }
        if (webRtcAudioCallManager == null) {
            initializeAudioCall(websocketUrl, deviceId)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        frameHandler.removeCallbacksAndMessages(null)
        webSocketClient?.disconnect()
        webRtcAudioCallManager?.disconnect()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjectionCallback?.let { callback ->
            mediaProjection?.unregisterCallback(callback)
        }
        mediaProjection?.stop()
        super.onDestroy()
    }

    override fun onConnected() {
        if (pendingSupportRequestOnConnect) {
            webSocketClient?.sendSupportRequest()
            pendingSupportRequestOnConnect = false
            updateNotification("Đã gửi yêu cầu hỗ trợ, đang chờ Admin Web nhận hỗ trợ")
            return
        }
        updateNotification(
            if (projectionStarted) {
                "Đã kết nối backend, đang stream màn hình"
            } else {
                "Đã kết nối backend, sẵn sàng chia sẻ màn hình"
            }
        )
    }

    override fun onDisconnected() {
        updateNotification("WebSocket bị ngắt, chờ service khởi động lại")
    }

    override fun onRemoteMessage(message: RemoteMessage) {
        when (message.type) {
            "COMMAND" -> handleCommand(message)
            "SUPPORT_REQUEST_ACK" -> updateNotification("Yêu cầu hỗ trợ đã được ghi nhận")
            "SUPPORT_ENDED" -> {
                updateNotification("Phiên hỗ trợ đã kết thúc")
                stopSelf()
            }
            "CONTROL_REQUESTED" -> handleControlRequest(message)
            "CONTROL_RELEASED" -> {
                cancelIncomingControlNotification()
                pendingControlViewerId = null
                pendingControlViewerName = null
                updateNotification("Phiên điều khiển từ xa đã kết thúc")
            }
            "SUPPORT_ACCEPTED" -> {
                if (projectionStarted) {
                    updateNotification("Admin đã nhận hỗ trợ, thiết bị đang chia sẻ màn hình")
                } else {
                    startStreamingIfReady()
                }
            }
        }
    }

    override fun onIncomingCall(viewerName: String?) {
        val caller = viewerName ?: "Admin Web"
        showIncomingCallNotification(caller)
        updateNotification("Có cuộc gọi đến từ $caller")
    }

    override fun onIncomingCallCancelled() {
        cancelIncomingCallNotification()
        updateNotification("Phiên audio call đã kết thúc")
    }

    private fun handleCommand(message: RemoteMessage) {
        when (message.command) {
            "TAP" -> {
                val success = if (message.x != null && message.y != null) {
                    val tapPoint = toScreenPoint(message.x, message.y)
                    RemoteAccessibilityService.performTap(tapPoint.first, tapPoint.second)
                } else {
                    false
                }

                webSocketClient?.sendCommandResult(
                    requestId = message.requestId,
                    status = if (success) "OK" else "FAILED",
                    errorMessage = if (success) null else "Accessibility TAP chưa sẵn sàng"
                )
            }
            "SWIPE" -> {
                val success = if (
                    message.startX != null &&
                    message.startY != null &&
                    message.endX != null &&
                    message.endY != null &&
                    message.durationMs != null
                ) {
                    val startPoint = toScreenPoint(message.startX, message.startY)
                    val endPoint = toScreenPoint(message.endX, message.endY)
                    RemoteAccessibilityService.performSwipe(
                        startPoint.first,
                        startPoint.second,
                        endPoint.first,
                        endPoint.second,
                        message.durationMs
                    )
                } else {
                    false
                }

                webSocketClient?.sendCommandResult(
                    requestId = message.requestId,
                    status = if (success) "OK" else "FAILED",
                    errorMessage = if (success) null else "Accessibility SWIPE chưa sẵn sàng"
                )
            }
            "TYPE_TEXT" -> {
                val success = if (!message.text.isNullOrEmpty()) {
                    RemoteAccessibilityService.performSetText(message.text)
                } else {
                    false
                }

                webSocketClient?.sendCommandResult(
                    requestId = message.requestId,
                    status = if (success) "OK" else "FAILED",
                    errorMessage = if (success) null else "Không tìm thấy ô nhập liệu đang focus để gán văn bản"
                )
            }
        }
    }

    private fun handleControlRequest(message: RemoteMessage) {
        pendingControlViewerId = message.viewerId
        pendingControlViewerName = message.viewerName ?: "Admin Web"
        showIncomingControlNotification(pendingControlViewerName ?: "Admin Web")
        updateNotification("Có yêu cầu điều khiển từ ${pendingControlViewerName ?: "Admin Web"}")
    }

    private fun initializeProjection(resultCode: Int, projectionData: Intent) {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = resources.displayMetrics

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds: Rect = windowManager.currentWindowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
        } else {
            val legacyMetrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(legacyMetrics)
            screenWidth = legacyMetrics.widthPixels
            screenHeight = legacyMetrics.heightPixels
        }

        screenDensity = metrics.densityDpi

        mediaProjection = projectionManager.getMediaProjection(resultCode, projectionData)
        val projection = requireNotNull(mediaProjection) { "MediaProjection must not be null after consent" }
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i("ScreenCaptureService", "MediaProjection stopped by system")
                frameHandler.removeCallbacksAndMessages(null)
                virtualDisplay?.release()
                virtualDisplay = null
                imageReader?.close()
                imageReader = null
                stopSelf()
            }
        }
        mediaProjectionCallback = callback
        projection.registerCallback(callback, frameHandler)
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection.createVirtualDisplay(
            "IHubRemoteCapture",
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )
    }

    private fun startProjectionForeground() {
        val notification = buildNotification("Đang chuẩn bị remote agent")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        projectionStarted = true
        baseForegroundStarted = true
    }

    private fun startStreamingIfReady() {
        val projectionData = storedProjectionData
        if (projectionStarted || projectionData == null) {
            updateNotification(
                if (projectionStarted) {
                    "Admin đã nhận hỗ trợ, thiết bị đang chia sẻ màn hình"
                } else {
                    "Chưa có quyền chia sẻ màn hình để bắt đầu stream"
                }
            )
            return
        }

        startProjectionForeground()
        initializeProjection(storedProjectionResultCode, projectionData)
        startFrameLoop()
        updateNotification("Admin đã nhận hỗ trợ, đang stream màn hình")
    }

    private fun startBaseForeground() {
        startForeground(NOTIFICATION_ID, buildNotification("Đang kết nối remote agent"))
        baseForegroundStarted = true
    }

    private fun initializeWebSocket(websocketUrl: String, deviceId: String, deviceName: String) {
        webSocketClient = RemoteWebSocketClient(
            websocketUrl = websocketUrl,
            deviceId = deviceId,
            deviceName = deviceName,
            agentVersion = BuildConfig.VERSION_NAME,
            callback = this
        ).also { it.connect() }
    }

    private fun initializeAudioCall(websocketUrl: String, deviceId: String) {
        val signalingUrl = websocketUrl.replace("/ws/remote", "/ws/webrtc")
        webRtcAudioCallManager?.disconnect()
        webRtcAudioCallManager = WebRtcAudioCallManager(
            context = applicationContext,
            signalingUrl = signalingUrl,
            deviceId = deviceId,
            listener = this
        ).also { it.connect() }
    }

    private fun startFrameLoop() {
        frameHandler.removeCallbacks(frameRunnable)
        frameHandler.post(frameRunnable)
    }

    private fun sendLatestFrame() {
        val image = imageReader?.acquireLatestImage() ?: return

        try {
            val plane = image.planes.firstOrNull() ?: return
            val bitmap = imageToBitmap(image.width, image.height, plane.buffer, plane.pixelStride, plane.rowStride)
            val scaledBitmap = scaleBitmap(bitmap)
            streamedFrameWidth = scaledBitmap.width
            streamedFrameHeight = scaledBitmap.height
            val imageBase64 = encodeBitmap(scaledBitmap)

            webSocketClient?.sendScreenFrame(
                width = scaledBitmap.width,
                height = scaledBitmap.height,
                timestamp = System.currentTimeMillis(),
                imageBase64 = imageBase64
            )
        } catch (exception: Exception) {
            Log.e("ScreenCaptureService", "Failed to send frame", exception)
        } finally {
            image.close()
        }
    }

    private fun imageToBitmap(
        width: Int,
        height: Int,
        buffer: ByteBuffer,
        pixelStride: Int,
        rowStride: Int
    ): Bitmap {
        val rowPadding = rowStride - pixelStride * width
        val bitmap = Bitmap.createBitmap(
            width + rowPadding / pixelStride,
            height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        return Bitmap.createBitmap(bitmap, 0, 0, width, height)
    }

    private fun scaleBitmap(bitmap: Bitmap): Bitmap {
        val targetWidth = MAX_FRAME_WIDTH
        if (bitmap.width <= targetWidth) {
            return bitmap
        }

        val ratio = targetWidth.toFloat() / bitmap.width.toFloat()
        val targetHeight = (bitmap.height * ratio).toInt()
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    private fun encodeBitmap(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }

    private fun toScreenPoint(x: Int, y: Int): Pair<Int, Int> {
        if (streamedFrameWidth <= 0 || streamedFrameHeight <= 0 || screenWidth <= 0 || screenHeight <= 0) {
            return x to y
        }

        val scaledX = (x.toFloat() * screenWidth.toFloat() / streamedFrameWidth.toFloat()).toInt()
        val scaledY = (y.toFloat() * screenHeight.toFloat() / streamedFrameHeight.toFloat()).toInt()

        return scaledX.coerceIn(0, screenWidth - 1) to scaledY.coerceIn(0, screenHeight - 1)
    }

    private fun buildNotification(contentText: String): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("IHUB Remote Agent")
            .setContentText(contentText)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun showIncomingCallNotification(caller: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val acceptIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ACTION_ACCEPT_CALL
        }
        val rejectIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ACTION_REJECT_CALL
        }

        val acceptPendingIntent = PendingIntent.getService(
            this,
            11,
            acceptIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val rejectPendingIntent = PendingIntent.getService(
            this,
            12,
            rejectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CALL_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle("Cuộc gọi đến")
            .setContentText("$caller đang gọi tới $activeDeviceName")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .addAction(android.R.drawable.sym_action_call, "Đồng ý", acceptPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Từ chối", rejectPendingIntent)
            .build()

        notificationManager.notify(INCOMING_CALL_NOTIFICATION_ID, notification)
    }

    private fun showIncomingControlNotification(viewerName: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val approveIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ACTION_APPROVE_CONTROL
        }
        val rejectIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ACTION_REJECT_CONTROL
        }

        val approvePendingIntent = PendingIntent.getService(
            this,
            21,
            approveIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val rejectPendingIntent = PendingIntent.getService(
            this,
            22,
            rejectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CONTROL_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Yêu cầu điều khiển thiết bị")
            .setContentText("$viewerName muốn thao tác trực tiếp trên màn hình IHUB")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setOngoing(true)
            .addAction(android.R.drawable.checkbox_on_background, "Đồng ý", approvePendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Từ chối", rejectPendingIntent)
            .build()

        notificationManager.notify(INCOMING_CONTROL_NOTIFICATION_ID, notification)
    }

    private fun cancelIncomingCallNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(INCOMING_CALL_NOTIFICATION_ID)
    }

    private fun cancelIncomingControlNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(INCOMING_CONTROL_NOTIFICATION_ID)
    }

    private fun updateNotification(contentText: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildNotification(contentText))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val channel = NotificationChannel(
            CHANNEL_ID,
            "IHUB Remote Agent",
            NotificationManager.IMPORTANCE_LOW
        )
        val callChannel = NotificationChannel(
            CALL_CHANNEL_ID,
            "IHUB Incoming Calls",
            NotificationManager.IMPORTANCE_HIGH
        )
        val controlChannel = NotificationChannel(
            CONTROL_CHANNEL_ID,
            "IHUB Control Requests",
            NotificationManager.IMPORTANCE_HIGH
        )
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
        notificationManager.createNotificationChannel(callChannel)
        notificationManager.createNotificationChannel(controlChannel)
    }

    companion object {
        private const val CHANNEL_ID = "ihub_remote_agent"
        private const val CALL_CHANNEL_ID = "ihub_remote_agent_calls"
        private const val CONTROL_CHANNEL_ID = "ihub_remote_agent_controls"
        private const val NOTIFICATION_ID = 1010
        private const val INCOMING_CALL_NOTIFICATION_ID = 1011
        private const val INCOMING_CONTROL_NOTIFICATION_ID = 1012
        private const val MAX_FRAME_WIDTH = 540
        private const val JPEG_QUALITY = 55
        private const val FRAME_INTERVAL_MS = 500L
        private const val ACTION_REQUEST_SUPPORT = "com.ihub.monitor.agent.action.REQUEST_SUPPORT"
        private const val ACTION_APPROVE_CONTROL = "com.ihub.monitor.agent.action.APPROVE_CONTROL"
        private const val ACTION_REJECT_CONTROL = "com.ihub.monitor.agent.action.REJECT_CONTROL"
        private const val ACTION_ACCEPT_CALL = "com.ihub.monitor.agent.action.ACCEPT_CALL"
        private const val ACTION_REJECT_CALL = "com.ihub.monitor.agent.action.REJECT_CALL"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_PROJECTION_DATA = "extra_projection_data"
        const val EXTRA_WEBSOCKET_URL = "extra_websocket_url"
        const val EXTRA_DEVICE_ID = "extra_device_id"
        const val EXTRA_DEVICE_NAME = "extra_device_name"
        const val EXTRA_SUPPORT_REQUEST_ON_CONNECT = "extra_support_request_on_connect"

        fun buildIntent(
            context: Context,
            websocketUrl: String,
            deviceId: String,
            deviceName: String,
            resultCode: Int,
            projectionData: Intent
        ): Intent = Intent(context, ScreenCaptureService::class.java).apply {
            putExtra(EXTRA_WEBSOCKET_URL, websocketUrl)
            putExtra(EXTRA_DEVICE_ID, deviceId)
            putExtra(EXTRA_DEVICE_NAME, deviceName)
            putExtra(EXTRA_RESULT_CODE, resultCode)
            putExtra(EXTRA_PROJECTION_DATA, projectionData)
        }

        fun buildConnectIntent(
            context: Context,
            websocketUrl: String,
            deviceId: String,
            deviceName: String,
            requestSupportOnConnect: Boolean,
            resultCode: Int,
            projectionData: Intent
        ): Intent = Intent(context, ScreenCaptureService::class.java).apply {
            putExtra(EXTRA_WEBSOCKET_URL, websocketUrl)
            putExtra(EXTRA_DEVICE_ID, deviceId)
            putExtra(EXTRA_DEVICE_NAME, deviceName)
            putExtra(EXTRA_SUPPORT_REQUEST_ON_CONNECT, requestSupportOnConnect)
            putExtra(EXTRA_RESULT_CODE, resultCode)
            putExtra(EXTRA_PROJECTION_DATA, projectionData)
        }

        fun buildSupportRequestIntent(context: Context): Intent =
            Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_REQUEST_SUPPORT
            }
    }
}
