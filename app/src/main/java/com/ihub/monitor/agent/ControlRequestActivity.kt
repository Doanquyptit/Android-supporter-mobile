package com.ihub.monitor.agent

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class ControlRequestActivity : AppCompatActivity() {

    private lateinit var titleView: TextView
    private lateinit var messageView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_control_request)

        titleView = findViewById(R.id.controlRequestTitle)
        messageView = findViewById(R.id.controlRequestMessage)

        findViewById<Button>(R.id.approveControlButton).setOnClickListener {
            startService(Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_APPROVE_CONTROL
            })
            finish()
        }

        findViewById<Button>(R.id.rejectControlButton).setOnClickListener {
            startService(Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_REJECT_CONTROL
            })
            finish()
        }

        findViewById<Button>(R.id.closeControlRequestButton).setOnClickListener {
            finish()
        }

        renderContent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        renderContent(intent)
    }

    private fun renderContent(intent: Intent) {
        val viewerName = intent.getStringExtra(EXTRA_VIEWER_NAME)?.ifBlank { null } ?: "Admin Web"
        val deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME)?.ifBlank { null } ?: "thiết bị IHUB"

        titleView.text = getString(R.string.control_request_title)
        messageView.text = getString(R.string.control_request_message, viewerName, deviceName)
    }

    companion object {
        private const val EXTRA_VIEWER_NAME = "extra_viewer_name"
        private const val EXTRA_DEVICE_NAME = "extra_device_name"

        fun buildIntent(context: Context, viewerName: String?, deviceName: String?): Intent {
            return Intent(context, ControlRequestActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_VIEWER_NAME, viewerName)
                putExtra(EXTRA_DEVICE_NAME, deviceName)
            }
        }
    }
}
