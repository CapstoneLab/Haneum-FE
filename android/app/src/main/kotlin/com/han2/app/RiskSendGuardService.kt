package com.han2.app

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView

class RiskSendGuardService : AccessibilityService() {
    private var riskActive = false
    private var blocker: View? = null
    private var confirmationOverlay: View? = null
    private var pendingSendNode: AccessibilityNodeInfo? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                RiskGuardContract.ACTION_RISK_STATE -> {
                    riskActive = intent.getBooleanExtra(RiskGuardContract.EXTRA_DANGER, false)
                    if (riskActive) refreshSendBlocker() else removeAllOverlays()
                }
                RiskGuardContract.ACTION_CONFIRM_SEND -> if (
                    intent.getStringExtra(RiskGuardContract.EXTRA_SOURCE) == RiskGuardContract.SOURCE_ACCESSIBILITY
                ) {
                    val node = pendingSendNode
                    pendingSendNode = null
                    riskActive = false
                    removeAllOverlays()
                    node?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    node?.recycle()
                }
                RiskGuardContract.ACTION_CANCEL_SEND -> if (
                    intent.getStringExtra(RiskGuardContract.EXTRA_SOURCE) == RiskGuardContract.SOURCE_ACCESSIBILITY
                ) {
                    pendingSendNode?.recycle()
                    pendingSendNode = null
                    removeConfirmationOverlay()
                    refreshSendBlocker()
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val filter = IntentFilter().apply {
            addAction(RiskGuardContract.ACTION_RISK_STATE)
            addAction(RiskGuardContract.ACTION_CONFIRM_SEND)
            addAction(RiskGuardContract.ACTION_CANCEL_SEND)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(receiver, filter)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (riskActive && confirmationOverlay == null) refreshSendBlocker()
    }

    override fun onInterrupt() = removeAllOverlays()

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        removeAllOverlays()
        pendingSendNode?.recycle()
        super.onDestroy()
    }

    private fun refreshSendBlocker() {
        if (confirmationOverlay != null) return
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() == packageName) {
            root.recycle()
            removeBlocker()
            return
        }
        val node = findSendButton(root)
        root.recycle()
        if (node == null) {
            removeBlocker()
            return
        }
        val bounds = Rect().also(node::getBoundsInScreen)
        if (bounds.isEmpty) {
            node.recycle()
            return
        }
        removeBlocker()
        val view = View(this).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_UP) {
                    pendingSendNode?.recycle()
                    pendingSendNode = AccessibilityNodeInfo.obtain(node)
                    showConfirmationOverlay()
                }
                true
            }
        }
        val params = WindowManager.LayoutParams(
            bounds.width().coerceAtLeast(1),
            bounds.height().coerceAtLeast(1),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bounds.left
            y = bounds.top
        }
        blocker = view
        (getSystemService(WINDOW_SERVICE) as WindowManager).addView(view, params)
        node.recycle()
    }

    private fun showConfirmationOverlay() {
        removeBlocker()
        if (confirmationOverlay != null) return
        val scrim = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(Color.argb(105, 0, 0, 0))
            isClickable = true
            addView(LinearLayout(this@RiskSendGuardService).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(24), dp(24), dp(18))
                background = rounded(Color.WHITE, 18)
                addView(TextView(this@RiskSendGuardService).apply {
                    text = "민감정보가 감지되었습니다."
                    textSize = 22f
                    setTextColor(Color.rgb(157, 40, 49))
                    setTypeface(typeface, Typeface.BOLD)
                })
                addView(TextView(this@RiskSendGuardService).apply {
                    text = "이 내용을 전송하시겠습니까?"
                    textSize = 16f
                    setTextColor(Color.rgb(45, 49, 57))
                    setPadding(0, dp(12), 0, dp(22))
                })
                addView(LinearLayout(this@RiskSendGuardService).apply {
                    gravity = Gravity.END
                    addView(dialogButton("수정하기", false) {
                        pendingSendNode?.recycle()
                        pendingSendNode = null
                        removeConfirmationOverlay()
                        refreshSendBlocker()
                    })
                    addView(dialogButton("그래도 전송", true) {
                        val node = pendingSendNode
                        pendingSendNode = null
                        riskActive = false
                        removeAllOverlays()
                        node?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        node?.recycle()
                    })
                })
            }, LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        confirmationOverlay = scrim
        (getSystemService(WINDOW_SERVICE) as WindowManager).addView(scrim, params)
    }

    private fun dialogButton(label: String, danger: Boolean, action: () -> Unit) =
        TextView(this).apply {
            text = label
            textSize = 15f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (danger) Color.WHITE else Color.rgb(0, 137, 123))
            background = rounded(if (danger) Color.rgb(0, 150, 136) else Color.TRANSPARENT, 12)
            setPadding(dp(16), dp(11), dp(16), dp(11))
            setOnClickListener { action() }
        }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun findSendButton(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val found = findSendButton(child)
            child.recycle()
            if (found != null) return found
        }
        val descriptor = buildString {
            append(listOfNotNull(node.text, node.contentDescription, node.viewIdResourceName).joinToString(" "))
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                append(' ')
                append(listOfNotNull(child.text, child.contentDescription, child.viewIdResourceName).joinToString(" "))
                child.recycle()
            }
        }.lowercase()
        val bounds = Rect().also(node::getBoundsInScreen)
        val density = resources.displayMetrics.density
        val isButtonSized = bounds.width() in 24..(120 * density).toInt() &&
            bounds.height() in 24..(120 * density).toInt()
        if (node.isClickable && isButtonSized && SEND_MARKERS.any(descriptor::contains)) {
            return AccessibilityNodeInfo.obtain(node)
        }
        return null
    }

    private fun removeBlocker() {
        blocker?.let { runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } }
        blocker = null
    }

    private fun removeConfirmationOverlay() {
        confirmationOverlay?.let {
            runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) }
        }
        confirmationOverlay = null
    }

    private fun removeAllOverlays() {
        removeBlocker()
        removeConfirmationOverlay()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private val SEND_MARKERS = listOf(
            "compose:draft:send", "send sms", "send message", "send mms", "compose_send", "send_button",
            "메시지 보내기", "sms 보내기", "전송 버튼"
        )
    }
}
