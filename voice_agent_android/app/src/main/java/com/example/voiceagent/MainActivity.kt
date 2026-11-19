package com.example.voiceagent

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var apiKeyEditText: EditText
    private lateinit var btnSaveKey: Button
    private lateinit var btnAccessibility: Button
    private lateinit var btnOverlay: Button
    private lateinit var btnMicPermission: Button
    private lateinit var btnToggleService: Button
    private lateinit var statusSummary: TextView

    companion object {
        private const val PERMISSION_REQ_RECORD_AUDIO = 2001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        apiKeyEditText = findViewById(R.id.etApiKey)
        btnSaveKey = findViewById(R.id.btnSaveApiKey)
        btnAccessibility = findViewById(R.id.btnAccessibility)
        btnOverlay = findViewById(R.id.btnOverlay)
        btnMicPermission = findViewById(R.id.btnMicPermission)
        btnToggleService = findViewById(R.id.btnToggleService)
        statusSummary = findViewById(R.id.tvStatusSummary)

        loadSavedApiKey()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }

    private fun loadSavedApiKey() {
        val prefs = getSharedPreferences("voice_agent_prefs", Context.MODE_PRIVATE)
        val key = prefs.getString("gemini_api_key", "") ?: ""
        apiKeyEditText.setText(key)
    }

    private fun setupListeners() {
        btnSaveKey.setOnClickListener {
            val key = apiKeyEditText.text.toString().trim()
            if (key.isNotEmpty()) {
                val prefs = getSharedPreferences("voice_agent_prefs", Context.MODE_PRIVATE)
                prefs.edit().putString("gemini_api_key", key).apply()
                Toast.makeText(this, "Gemini API Key saved", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Please enter Gemini API Key", Toast.LENGTH_SHORT).show()
            }
        }

        btnAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            Toast.makeText(this, "Find and enable Voice Agent in the list", Toast.LENGTH_LONG).show()
        }

        btnOverlay.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!Settings.canDrawOverlays(this)) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                } else {
                    Toast.makeText(this, "Overlay permission already granted", Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnMicPermission.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.RECORD_AUDIO),
                    PERMISSION_REQ_RECORD_AUDIO
                )
            } else {
                Toast.makeText(this, "Microphone permission already granted", Toast.LENGTH_SHORT).show()
            }
        }

        btnToggleService.setOnClickListener {
            toggleFloatingService()
        }
    }

    private fun updatePermissionStatus() {
        val hasAccessibility = VoiceAgentAccessibilityService.instance != null
        val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true
        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        btnAccessibility.text = if (hasAccessibility) "1. Accessibility Service: Active ✅" else "1. Enable Accessibility Service ⚠️"
        btnOverlay.text = if (hasOverlay) "2. Display Over Other Apps: Granted ✅" else "2. Allow Display Over Other Apps ⚠️"
        btnMicPermission.text = if (hasMic) "3. Microphone Permission: Granted ✅" else "3. Request Microphone Permission ⚠️"

        val allReady = hasAccessibility && hasOverlay && hasMic
        btnToggleService.isEnabled = allReady

        if (FloatingOverlayService.isRunning) {
            btnToggleService.text = "Stop Floating Assistant ⏹️"
        } else {
            btnToggleService.text = "Start Floating Assistant 🚀"
        }

        statusSummary.text = if (allReady) {
            "All permissions granted. Ready to start floating assistant."
        } else {
            "Please enable all three permissions above to allow the assistant to control the screen."
        }
    }

    private fun toggleFloatingService() {
        val serviceIntent = Intent(this, FloatingOverlayService::class.java)
        if (FloatingOverlayService.isRunning) {
            stopService(serviceIntent)
            btnToggleService.text = "Start Floating Assistant 🚀"
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            btnToggleService.text = "Stop Floating Assistant ⏹️"
            Toast.makeText(this, "Floating assistant started on screen!", Toast.LENGTH_SHORT).show()
            finish() // Minimize app so user sees their home screen
        }
    }
}
