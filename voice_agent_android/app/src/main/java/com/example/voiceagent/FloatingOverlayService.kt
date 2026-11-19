package com.example.voiceagent

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.app.NotificationCompat

class FloatingOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var micButton: FrameLayout? = null
    private var micIconView: ImageView? = null

    private var speechManager: SpeechManager? = null
    private var agentBrain: AgentBrain? = null
    private val mainHandler = Handler(Looper.getMainLooper())

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
        startForegroundNotification()
        setupAgentBrain()
        createFloatingWidget()
    }

    private fun dpToPx(dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            resources.displayMetrics
        ).toInt()
    }

    private fun startForegroundNotification() {
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
            .setContentText("دکمه میکروفون شناور آماده است")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun setupAgentBrain() {
        val prefs = getSharedPreferences("voice_agent_prefs", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("gemini_api_key", "") ?: ""

        agentBrain = AgentBrain(apiKey) { statusText ->
            if (statusText.contains("مرحله") || statusText.contains("انجام") || statusText.contains("تحلیل")) {
                setMicVisualState(MicState.WORKING)
            } else if (statusText.contains("موفقیت") || statusText.contains("پایان") || statusText.contains("خطا")) {
                mainHandler.postDelayed({
                    setMicVisualState(MicState.IDLE)
                }, 2000)
            }
        }

        speechManager = SpeechManager(
            context = this,
            onResult = { voiceText ->
                setMicVisualState(MicState.WORKING)
                agentBrain?.executeGoal(voiceText)
            },
            onError = { errorMsg ->
                mainHandler.postDelayed({
                    setMicVisualState(MicState.IDLE)
                }, 2000)
            },
            onReady = {
                setMicVisualState(MicState.LISTENING)
            }
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createFloatingWidget() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val btnSize = dpToPx(72f)
        val iconSize = dpToPx(38f)

        val params = WindowManager.LayoutParams(
            btnSize,
            btnSize,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dpToPx(24f)
            y = dpToPx(200f)
        }

        micButton = FrameLayout(this).apply {
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#4B5563")) // IDLE: Gray
                setStroke(dpToPx(3.5f), Color.parseColor("#9CA3AF")) // Gray border
            }
            background = bg
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                elevation = dpToPx(10f).toFloat()
            }
        }

        micIconView = ImageView(this).apply {
            val lp = FrameLayout.LayoutParams(iconSize, iconSize).apply {
                gravity = Gravity.CENTER
            }
            layoutParams = lp
            setImageDrawable(MicrophoneVectorDrawable())
        }

        micButton?.addView(micIconView)

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isClick = true

        micButton?.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isClick = true
                    v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(100).start()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > dpToPx(8f) || Math.abs(dy) > dpToPx(8f)) {
                        isClick = false
                    }
                    params.x = initialX + dx
                    params.y = initialY + dy
                    windowManager.updateViewLayout(v, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).setInterpolator(OvershootInterpolator()).start()
                    if (isClick) {
                        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        onMicClicked()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                    true
                }
                else -> false
            }
        }

        windowManager.addView(micButton, params)
    }

    private fun onMicClicked() {
        setMicVisualState(MicState.LISTENING)
        mainHandler.post {
            speechManager?.startListening()
        }
    }

    private enum class MicState {
        IDLE,
        LISTENING,
        WORKING
    }

    private fun setMicVisualState(state: MicState) {
        mainHandler.post {
            val bg = micButton?.background as? GradientDrawable ?: return@post
            when (state) {
                MicState.IDLE -> {
                    bg.setColor(Color.parseColor("#4B5563")) // Gray
                    bg.setStroke(dpToPx(3.5f), Color.parseColor("#9CA3AF")) // Gray border
                    micButton?.animate()?.scaleX(1.0f)?.scaleY(1.0f)?.setDuration(200)?.start()
                }
                MicState.LISTENING -> {
                    bg.setColor(Color.parseColor("#10B981")) // Green
                    bg.setStroke(dpToPx(3.5f), Color.parseColor("#6EE7B7"))
                    micButton?.animate()?.scaleX(1.12f)?.scaleY(1.12f)?.setDuration(200)?.start()
                }
                MicState.WORKING -> {
                    bg.setColor(Color.parseColor("#EF4444")) // Red
                    bg.setStroke(dpToPx(3.5f), Color.parseColor("#FCA5A5"))
                    micButton?.animate()?.scaleX(1.05f)?.scaleY(1.05f)?.setDuration(200)?.start()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        speechManager?.stopListening()
        if (micButton != null) {
            windowManager.removeView(micButton)
        }
    }

    private inner class MicrophoneVectorDrawable : android.graphics.drawable.Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }

        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }

        override fun draw(canvas: Canvas) {
            val bounds = bounds
            val w = bounds.width().toFloat()
            val h = bounds.height().toFloat()
            if (w <= 0 || h <= 0) return

            strokePaint.strokeWidth = w * 0.10f

            val cx = bounds.left + w / 2f
            val cy = bounds.top + h / 2f

            // بدنه میکروفون
            val capWidth = w * 0.36f
            val capHeight = h * 0.52f
            val capLeft = cx - capWidth / 2f
            val capTop = cy - capHeight * 0.60f
            val capRect = RectF(capLeft, capTop, capLeft + capWidth, capTop + capHeight)
            canvas.drawRoundRect(capRect, capWidth / 2f, capWidth / 2f, paint)

            // پایه هلالی میکروفون
            val cradleWidth = w * 0.60f
            val cradleHeight = h * 0.44f
            val cradleLeft = cx - cradleWidth / 2f
            val cradleTop = cy - cradleHeight * 0.20f
            val cradleRect = RectF(cradleLeft, cradleTop, cradleLeft + cradleWidth, cradleTop + cradleHeight)
            canvas.drawArc(cradleRect, 0f, 180f, false, strokePaint)

            // میله عمودی
            val stemTop = cradleTop + cradleHeight / 2f + (cradleHeight / 2f)
            val stemBottom = stemTop + h * 0.16f
            canvas.drawLine(cx, stemTop, cx, stemBottom, strokePaint)

            // پایه افقی
            val baseHalfWidth = w * 0.24f
            canvas.drawLine(cx - baseHalfWidth, stemBottom, cx + baseHalfWidth, stemBottom, strokePaint)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
            strokePaint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
            paint.colorFilter = colorFilter
            strokePaint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
