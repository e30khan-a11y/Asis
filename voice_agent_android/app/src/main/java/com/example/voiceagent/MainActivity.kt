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
                Toast.makeText(this, "کلید جمینای ذخیره شد", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "لطفاً کلید API را وارد کنید", Toast.LENGTH_SHORT).show()
            }
        }

        btnAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            Toast.makeText(this, "در لیست، برنامه Voice Agent را پیدا و فعال کنید", Toast.LENGTH_LONG).show()
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
                    Toast.makeText(this, "دسترسی دکمه شناور قبلاً داده شده است", Toast.LENGTH_SHORT).show()
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
                Toast.makeText(this, "دسترسی میکروفون داده شده است", Toast.LENGTH_SHORT).show()
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

        btnAccessibility.text = if (hasAccessibility) "۱. سرویس دسترسی: فعال ✅" else "۱. فعال‌سازی سرویس دسترسی ⚠️"
        btnOverlay.text = if (hasOverlay) "۲. نمایش روی سایر برنامه‌ها: فعال ✅" else "۲. مجوز نمایش روی برنامه‌ها ⚠️"
        btnMicPermission.text = if (hasMic) "۳. دسترسی میکروفون: مجاز ✅" else "۳. درخواست مجوز میکروفون ⚠️"

        val allReady = hasAccessibility && hasOverlay && hasMic
        btnToggleService.isEnabled = allReady

        if (FloatingOverlayService.isRunning) {
            btnToggleService.text = "توقف دستیار شناور ⏹️"
        } else {
            btnToggleService.text = "شروع دستیار شناور 🚀"
        }

        statusSummary.text = if (allReady) {
            "همه دسترسی‌ها آماده است. می‌توانید دکمه شناور را فعال کنید."
        } else {
            "لطفاً هر سه دسترسی بالا را فعال کنید تا برنامه بتواند گوشی را کنترل کند."
        }
    }

    private fun toggleFloatingService() {
        val serviceIntent = Intent(this, FloatingOverlayService::class.java)
        if (FloatingOverlayService.isRunning) {
            stopService(serviceIntent)
            btnToggleService.text = "شروع دستیار شناور 🚀"
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            btnToggleService.text = "توقف دستیار شناور ⏹️"
            Toast.makeText(this, "دکمه شناور روی صفحه ظاهر شد!", Toast.LENGTH_SHORT).show()
            finish() // Minimize app so user sees their home screen
        }
    }
}
