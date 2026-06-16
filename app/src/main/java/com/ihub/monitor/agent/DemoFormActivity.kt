package com.ihub.monitor.agent

import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class DemoFormActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_demo_form)

        findViewById<Button>(R.id.backToAgentButton).setOnClickListener {
            finish()
        }

        findViewById<Button>(R.id.submitDemoButton).setOnClickListener {
            Toast.makeText(this, R.string.demo_form_submit_message, Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.resetDemoButton).setOnClickListener {
            recreate()
        }
    }
}
