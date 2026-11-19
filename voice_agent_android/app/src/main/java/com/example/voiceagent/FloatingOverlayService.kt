package com.example.voiceagent

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat

class FloatingOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayLayout: LinearLayout? = null
    private var statusTextView: TextView? = null
    private var micButton: FrameLayout? = null

    private var speechManager: SpeechManager? = null
    private var agentBrain: AgentBrain? = null

    companion object {
        private const val CHANNEL_ID = "VoiceAgentOverlayChannel"
        private const val NOTIFICATION_ID = 1001
        var isRunning = false
            private set
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startForegroundServiceNotification()
        setupAgentBrain()
        createFloatingView()
    }

    private fun startForegroundServiceNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Voice Agent Floating Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("دستیار صوتی فعال است")
            .setContentText("دکمه شناور روی صفحه نمایش داده می‌شود")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun setupAgentBrain() {
        val prefs = getSharedPreferences("voice_agent_prefs", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("gemini_api_key", "") ?: ""

        agentBrain = AgentBrain(apiKey) { statusText ->
            updateStatusText(statusText)
        }

        speechManager = SpeechManager(
            context = this,
            onResult = { voiceText ->
                updateStatusText("فرمان دریافت شد: $voiceText")
                setMicVisualState(MicState.PROCESSING)
                agentBrain?.executeGoal(voiceText)
            },
            onError = { errorMsg ->
                updateStatusText(errorMsg)
                setMicVisualState(MicState.IDLE)
            },
            onReady = {
                updateStatusText("در حال گوش دادن... صحبت کنید")
                setMicVisualState(MicState.LISTENING)
            }
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createFloatingView() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 300
        }

        overlayLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // Status Card
        statusTextView = TextView(this).apply {
            text = "آماده فرمان صوتی"
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(24, 12, 24, 12)
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#CC1A1A1A"))
                cornerRadius = 24f
            }
            background = bg
            visibility = View.VISIBLE
        }

        // Mic Button
        micButton = FrameLayout(this).apply {
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#4285F4")) // Google Blue
            }
            background = bg
            setPadding(30, 30, 30, 30)

            val icon = ImageView(context).apply {
                setImageResource(android.R.drawable.ic_btn_speak_now)
                setColorFilter(Color.WHITE)
            }
            addView(icon)
        }

        overlayLayout?.addView(statusTextView)
        val space = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, 12)
        }
        overlayLayout?.addView(space)
        overlayLayout?.addView(micButton)

        // Drag and click handling
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isClick = true

        micButton?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isClick = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        isClick = false
                    }
                    params.x = initialX + dx
                    params.y = initialY + dy
                    windowManager.updateViewLayout(overlayLayout, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isClick) {
                        onMicClicked()
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(overlayLayout, params)
    }

    private fun onMicClicked() {
        speechManager?.startListening()
    }

    private enum class MicState { IDLE, LISTENING, PROCESSING }

    private fun setMicVisualState(state: MicState) {
        val color = when (state) {
            MicState.IDLE -> Color.parseColor("#4285F4") // Blue
            MicState.LISTENING -> Color.parseColor("#EA4335") // Red
            MicState.PROCESSING -> Color.parseColor("#FBBC05") // Yellow
        }
        (micButton?.background as? GradientDrawable)?.setColor(color)
    }

    private fun updateStatusText(text: String) {
        statusTextView?.post {
            statusTextView?.text = text
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        speechManager?.stopListening()
        if (overlayLayout != null) {
            windowManager.removeView(overlayLayout)
        }
    }
}
