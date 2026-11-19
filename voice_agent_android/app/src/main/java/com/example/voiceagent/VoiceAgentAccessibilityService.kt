package com.example.voiceagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class VoiceAgentAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "VoiceAgentService"
        var instance: VoiceAgentAccessibilityService? = null
            private set
    }

    private var currentScreenDump: ScreenDump? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility Service Connected successfully")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Events are handled on-demand when agent queries current screen
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility Service Interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
    }

    fun captureCurrentScreen(): ScreenDump {
        val root = rootInActiveWindow
        val dump = AccessibilityTreeParser.parse(root)
        currentScreenDump = dump
        return dump
    }

    fun clickNode(nodeId: Int): Boolean {
        val node = currentScreenDump?.nodeMap?.get(nodeId)
        if (node != null) {
            // First try native click action
            if (node.nodeRef.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            // If parent is clickable, click parent
            var parent = node.nodeRef.parent
            while (parent != null) {
                if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }
                parent = parent.parent
            }
            // Fallback: click coordinates via dispatchGesture
            val bounds = node.bounds
            val centerX = bounds.centerX().toFloat()
            val centerY = bounds.centerY().toFloat()
            return clickCoordinates(centerX, centerY)
        }
        return false
    }

    fun clickCoordinates(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val path = Path().apply {
                moveTo(x, y)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, 100)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            return dispatchGesture(gesture, null, null)
        }
        return false
    }

    fun typeText(nodeId: Int, text: String): Boolean {
        val node = currentScreenDump?.nodeMap?.get(nodeId)
        if (node != null) {
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            return node.nodeRef.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        }
        return false
    }

    fun scroll(forward: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val action = if (forward) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        return root.performAction(action)
    }

    fun performBack(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    fun performHome(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_HOME)
    }

    fun performRecents(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_RECENTS)
    }

    fun launchAppByName(nameQuery: String): Boolean {
        val pm = packageManager
        val query = nameQuery.trim().lowercase()

        // Common Aliases
        val targetPackage = when (query) {
            "sms", "message", "messaging", "پیامک", "پیام" -> {
                android.provider.Telephony.Sms.getDefaultSmsPackage(this)
            }
            "whatsapp", "واتساپ" -> "com.whatsapp"
            "telegram", "تلگرام" -> "org.telegram.messenger"
            "music", "موزیک", "آهنگ" -> "com.google.android.apps.youtube.music"
            "youtube", "یوتیوب" -> "com.google.android.youtube"
            "maps", "map", "نقشه", "گوگل مپ" -> "com.google.android.apps.maps"
            "neshan", "نشان" -> "org.neshan.maps"
            "balad", "بلد" -> "ir.balad.maps"
            "settings", "تنظیمات" -> "com.android.settings"
            else -> null
        }

        if (targetPackage != null) {
            val intent = pm.getLaunchIntentForPackage(targetPackage)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                return true
            }
        }

        // Fuzzy match installed apps by app label
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        for (appInfo in packages) {
            val label = pm.getApplicationLabel(appInfo).toString().lowercase()
            if (label.contains(query) || appInfo.packageName.lowercase().contains(query)) {
                val intent = pm.getLaunchIntentForPackage(appInfo.packageName)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                    return true
                }
            }
        }
        return false
    }

    fun captureScreenshot(callback: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            takeScreenshot(Display.DEFAULT_DISPLAY, applicationContext.mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshotResult: ScreenshotResult) {
                    val hardwareBuffer = screenshotResult.hardwareBuffer
                    val colorSpace = screenshotResult.colorSpace
                    val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                    hardwareBuffer.close()
                    // Convert hardware bitmap to software bitmap for JPEG/PNG encoding
                    val softwareBitmap = bitmap?.copy(Bitmap.Config.ARGB_8888, false)
                    callback(softwareBitmap)
                }

                override fun onFailure(errorCode: Int) {
                    Log.e(TAG, "Screenshot failed with error code: $errorCode")
                    callback(null)
                }
            })
        } else {
            callback(null)
        }
    }
}
