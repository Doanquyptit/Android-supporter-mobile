package com.ihub.monitor.agent

import android.Manifest
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var deviceIdInput: EditText
    private lateinit var deviceNameInput: EditText
    private lateinit var statusView: TextView

    private val mediaProjectionManager by lazy {
        getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val serviceIntent = ScreenCaptureService.buildConnectIntent(
                context = this,
                websocketUrl = DEFAULT_WEBSOCKET_URL,
                deviceId = deviceIdInput.text.toString().trim(),
                deviceName = deviceNameInput.text.toString().trim(),
                requestSupportOnConnect = true,
                resultCode = result.resultCode,
                projectionData = result.data!!
            )

            try {
                startService(serviceIntent)
                statusView.text = getString(R.string.agent_status_support_requested)
            } catch (exception: Exception) {
                Log.e("MainActivity", "Failed to start ScreenCaptureService", exception)
                statusView.text = "Không thể khởi động agent: ${exception.message}"
            }
        } else {
            statusView.text = getString(R.string.agent_status_permission_denied)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        deviceIdInput = findViewById(R.id.deviceIdInput)
        deviceNameInput = findViewById(R.id.deviceNameInput)
        statusView = findViewById(R.id.statusView)

        deviceIdInput.setText(DEFAULT_DEVICE_ID)
        deviceNameInput.setText(DEFAULT_DEVICE_NAME)
        statusView.text = getString(R.string.agent_status_idle)

        findViewById<Button>(R.id.openAccessibilityButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.requestSupportButton).setOnClickListener {
            requestRuntimePermissions()
            statusView.text = getString(R.string.agent_status_requesting_permissions)
            screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
        }

        findViewById<Button>(R.id.stopAgentButton).setOnClickListener {
            stopService(Intent(this, ScreenCaptureService::class.java))
            statusView.text = getString(R.string.agent_status_stopped)
        }

        findViewById<Button>(R.id.openDemoFormButton).setOnClickListener {
            startActivity(Intent(this, DemoFormActivity::class.java))
        }

    }

    private fun requestRuntimePermissions() {
        val permissions = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
        requestPermissions(permissions, 1001)
    }

    companion object {
        private const val DEFAULT_WEBSOCKET_URL = "ws://10.125.20.102:8080/ws/remote"
        private const val DEFAULT_DEVICE_ID = "ihub-001"
        private const val DEFAULT_DEVICE_NAME = "IHUB 001"
    }
}
