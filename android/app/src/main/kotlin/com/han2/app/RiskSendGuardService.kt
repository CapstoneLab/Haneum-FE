package com.han2.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Path
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
    private var currentRiskLevel = RiskLevel.SAFE
    private var currentRiskPattern = ""
    private var currentInputPreview = ""
    private var monitoringActive = false
    private var monitoredPackage = ""
    private var blocker: View? = null
    private var blockerSendNode: AccessibilityNodeInfo? = null
    private val blockerBounds = Rect()
    private var confirmationShowing = false
    private var confirmationView: View? = null
    private var pendingSendNode: AccessibilityNodeInfo? = null
    private var pendingSendBounds: Rect? = null
    private var confirmationSource = RiskGuardContract.SOURCE_ACCESSIBILITY
    private var lastExternalSendState: Boolean? = null
    private var bypassPackage = ""
    private var bypassUntil = 0L
    private var monitoredDraftId = ""
    private val guardHandler = Handler(Looper.getMainLooper())
    private val guardRefresh = Runnable {
        if (monitoringActive && currentRiskLevel == RiskLevel.DANGER && !confirmationShowing) {
            refreshSendBlocker()
        }
    }
    private val sendButtonDetector by lazy {
        DefaultSendButtonDetector(resources.displayMetrics.density)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                RiskGuardContract.ACTION_RISK_STATE -> {
                    val incomingDraftId = intent.getStringExtra(RiskGuardContract.EXTRA_DRAFT_ID).orEmpty()
                    if (incomingDraftId.isNotBlank() && incomingDraftId != monitoredDraftId) {
                        monitoredDraftId = incomingDraftId
                        // Approval applies only to the message that was explicitly approved.
                        // A newly created draft must always be guarded again.
                        bypassPackage = ""
                        bypassUntil = 0L
                    }
                    riskActive = intent.getBooleanExtra(RiskGuardContract.EXTRA_DANGER, false)
                    monitoringActive = intent.getBooleanExtra(RiskGuardContract.EXTRA_HAS_DRAFT, false)
                    currentRiskLevel = runCatching {
                        RiskLevel.valueOf(intent.getStringExtra(RiskGuardContract.EXTRA_RISK_LEVEL).orEmpty())
                    }.getOrElse { RiskStateStore.read(this@RiskSendGuardService).level }
                    currentRiskPattern = intent.getStringExtra(RiskGuardContract.EXTRA_RISK_PATTERN)
                        ?: RiskStateStore.read(this@RiskSendGuardService).pattern
                    currentInputPreview = intent.getStringExtra(RiskGuardContract.EXTRA_INPUT_PREVIEW)
                        ?: RiskStateStore.read(this@RiskSendGuardService).inputPreview
                    riskActive = currentRiskLevel != RiskLevel.SAFE
                    monitoredPackage = intent.getStringExtra(RiskGuardContract.EXTRA_APP_PACKAGE)
                        ?: RiskStateStore.read(this@RiskSendGuardService).appPackage
                    if (currentRiskLevel == RiskLevel.DANGER && monitoringActive) {
                        // A detected draft always arms the guard. Never carry a previous
                        // one-shot approval or its timeout into another send attempt.
                        bypassPackage = ""
                        bypassUntil = 0L
                    }
                    // Once the user has attempted to send, only an explicit dialog choice may
                    // dismiss the confirmation. IME focus/lifecycle broadcasts must not flash it away.
                    if (confirmationShowing) return
                    if (pendingSendNode != null) {
                        when (currentRiskLevel) {
                            RiskLevel.DANGER -> showConfirmationOverlay()
                            RiskLevel.SAFE -> approveAndSend()
                            RiskLevel.WARNING -> Unit
                        }
                        return
                    }
                    if (monitoringActive) {
                        refreshSendBlocker()
                        if (currentRiskLevel == RiskLevel.DANGER) scheduleGuardRefreshes()
                    } else removeAllOverlays()
                }
                RiskGuardContract.ACTION_CONFIRM_SEND -> if (
                    intent.getStringExtra(RiskGuardContract.EXTRA_SOURCE) == RiskGuardContract.SOURCE_ACCESSIBILITY
                ) {
                    approveAndSend()
                }
                RiskGuardContract.ACTION_CANCEL_SEND -> if (
                    intent.getStringExtra(RiskGuardContract.EXTRA_SOURCE) == RiskGuardContract.SOURCE_ACCESSIBILITY
                ) {
                    cancelPendingSend()
                }
                RiskGuardContract.ACTION_REQUEST_CONFIRMATION -> if (
                    intent.getStringExtra(RiskGuardContract.EXTRA_SOURCE) == RiskGuardContract.SOURCE_KEYBOARD
                ) {
                    pendingSendNode?.recycle()
                    pendingSendNode = null
                    pendingSendBounds = null
                    showConfirmationOverlay(RiskGuardContract.SOURCE_KEYBOARD)
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
            addAction(RiskGuardContract.ACTION_REQUEST_CONFIRMATION)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(receiver, filter)
        val shared = RiskStateStore.read(this)
        monitoringActive = shared.hasDraft
        currentRiskLevel = shared.level
        riskActive = currentRiskLevel != RiskLevel.SAFE
        monitoredPackage = shared.appPackage
        if (monitoringActive) refreshSendBlocker()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (confirmationShowing) return
        val eventPackage = event?.packageName?.toString().orEmpty()
        if (SystemClock.uptimeMillis() < bypassUntil && eventPackage == bypassPackage) return
        if (monitoringActive) refreshSendBlocker() else removeAllOverlays()
    }

    override fun onInterrupt() = removeBlocker()

    override fun onDestroy() {
        guardHandler.removeCallbacks(guardRefresh)
        runCatching { unregisterReceiver(receiver) }
        removeAllOverlays()
        pendingSendNode?.recycle()
        pendingSendNode = null
        pendingSendBounds = null
        super.onDestroy()
    }

    private fun refreshSendBlocker() {
        if (confirmationShowing) return
        val root = rootInActiveWindow ?: return
        val foregroundPackage = root.packageName?.toString().orEmpty()
        if (foregroundPackage == packageName ||
            (monitoredPackage.isNotBlank() && foregroundPackage != monitoredPackage)) {
            root.recycle()
            removeBlocker()
            reportExternalSendTarget(false)
            return
        }
        val target = sendButtonDetector.find(root)
        root.recycle()
        if (target == null) {
            removeBlocker()
            reportExternalSendTarget(false)
            return
        }
        reportExternalSendTarget(true)
        val node = target.node
        val bounds = target.bounds
        if (bounds.isEmpty) {
            node.recycle()
            return
        }
        if (blocker != null) {
            blockerSendNode?.recycle()
            blockerSendNode = node
            blockerBounds.set(bounds)
            val updated = runCatching {
                (getSystemService(WINDOW_SERVICE) as WindowManager)
                    .updateViewLayout(blocker, blockerLayoutParams(bounds))
            }.isSuccess
            if (!updated) removeBlocker()
            return
        }

        blockerSendNode = node
        blockerBounds.set(bounds)
        val view = View(this).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            isClickable = true
            setOnTouchListener { _, event ->
                // Open on DOWN so an accessibility-tree refresh between DOWN and UP cannot
                // swallow the user's send-button tap and leave the button looking disabled.
                if (event.action == MotionEvent.ACTION_DOWN && !confirmationShowing) {
                    pendingSendNode?.recycle()
                    val currentNode = blockerSendNode ?: return@setOnTouchListener true
                    pendingSendNode = AccessibilityNodeInfo.obtain(currentNode)
                    pendingSendBounds = Rect(blockerBounds)
                    when (currentRiskLevel) {
                        RiskLevel.DANGER -> {
                            DetectionStore.recordSendAttempt(
                                this@RiskSendGuardService,
                                RiskResult(RiskLevel.DANGER, "민감정보가 감지되었습니다.", "전송 시도 시 위험정보가 감지되었습니다."),
                                currentRiskPattern,
                                currentInputPreview,
                                monitoredPackage
                            )
                            showConfirmationOverlay(RiskGuardContract.SOURCE_ACCESSIBILITY)
                        }
                        RiskLevel.SAFE -> approveAndSend()
                        RiskLevel.WARNING -> {
                            DetectionStore.recordSendAttempt(
                                this@RiskSendGuardService,
                                RiskResult(RiskLevel.WARNING, "주의가 필요한 입력이에요", "전송 시도 시 위험 가능성이 감지되었습니다."),
                                currentRiskPattern,
                                currentInputPreview,
                                monitoredPackage
                            )
                            removeBlocker()
                        }
                    }
                }
                true
            }
        }
        val params = blockerLayoutParams(bounds)
        blocker = view
        (getSystemService(WINDOW_SERVICE) as WindowManager).addView(view, params)
    }

    private fun blockerLayoutParams(bounds: Rect) = WindowManager.LayoutParams(
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

    private fun scheduleGuardRefreshes() {
        guardHandler.removeCallbacks(guardRefresh)
        guardHandler.postDelayed(guardRefresh, 120L)
        guardHandler.postDelayed(guardRefresh, 350L)
        guardHandler.postDelayed(guardRefresh, 700L)
    }

    private fun showConfirmationOverlay(source: String = RiskGuardContract.SOURCE_ACCESSIBILITY) {
        removeBlocker()
        if (confirmationShowing) return
        confirmationSource = source
        confirmationShowing = true

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(22), dp(24), dp(22), dp(20))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.rgb(194, 58, 67))
            }

            addView(TextView(this@RiskSendGuardService).apply {
                text = "민감정보가\n감지되었습니다."
                textSize = 27f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(157, 40, 49))
                setTypeface(typeface, Typeface.BOLD)
            }, LinearLayout.LayoutParams(-1, -2))

            addView(TextView(this@RiskSendGuardService).apply {
                text = "민감정보가 포함되어 있을 수\n있습니다.\n보내기 전에 내용을 다시 확인해\n주세요."
                textSize = 19f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(38, 42, 49))
                setPadding(0, dp(14), 0, dp(24))
            }, LinearLayout.LayoutParams(-1, -2))

            addView(LinearLayout(this@RiskSendGuardService).apply {
                gravity = Gravity.CENTER
                addView(confirmationButton("수정하기", false) {
                    resolveConfirmation(false)
                }, LinearLayout.LayoutParams(0, dp(64), 1f))
                addView(confirmationButton("그래도 전송", true) {
                    resolveConfirmation(true)
                }, LinearLayout.LayoutParams(0, dp(64), 1f).apply { marginStart = dp(10) })
            }, LinearLayout.LayoutParams(-1, -2))
        }

        val container = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(24), dp(18), dp(24))
            setBackgroundColor(Color.argb(105, 0, 0, 0))
            isClickable = true
            isFocusable = true
            addView(card, LinearLayout.LayoutParams(
                resources.displayMetrics.widthPixels.coerceAtMost(dp(342)),
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        confirmationView = container
        runCatching {
            (getSystemService(WINDOW_SERVICE) as WindowManager).addView(container, params)
        }.onFailure {
            confirmationView = null
            confirmationShowing = false
            if (source == RiskGuardContract.SOURCE_KEYBOARD) sendKeyboardConfirmation(false) else refreshSendBlocker()
        }
    }

    private fun confirmationButton(label: String, filled: Boolean, action: () -> Unit) =
        TextView(this).apply {
            text = label
            textSize = 19f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (filled) Color.WHITE else Color.rgb(157, 40, 49))
            background = GradientDrawable().apply {
                setColor(if (filled) Color.rgb(198, 61, 70) else Color.WHITE)
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), Color.rgb(210, 83, 91))
            }
            setOnClickListener { action() }
        }

    private fun resolveConfirmation(approved: Boolean) {
        if (confirmationSource == RiskGuardContract.SOURCE_KEYBOARD) {
            removeConfirmationOverlay()
            sendKeyboardConfirmation(approved)
            if (!approved && monitoringActive) refreshSendBlocker()
            return
        }
        if (approved) approveAndSend() else cancelPendingSend()
    }

    private fun sendKeyboardConfirmation(approved: Boolean) {
        sendBroadcast(Intent(
            if (approved) RiskGuardContract.ACTION_CONFIRM_SEND else RiskGuardContract.ACTION_CANCEL_SEND
        ).apply {
            setPackage(packageName)
            putExtra(RiskGuardContract.EXTRA_SOURCE, RiskGuardContract.SOURCE_KEYBOARD)
        })
    }

    private fun cancelPendingSend() {
        pendingSendNode?.recycle()
        pendingSendNode = null
        pendingSendBounds = null
        // "Edit" cancels only this send attempt. It is never an approval.
        // Keep the current sensitive draft armed and discard every bypass state.
        bypassPackage = ""
        bypassUntil = 0L
        monitoringActive = true
        removeConfirmationOverlay()
        refreshSendBlocker()
        if (currentRiskLevel == RiskLevel.DANGER) scheduleGuardRefreshes()
    }

    private fun approveAndSend() {
        val node = pendingSendNode
        val bounds = pendingSendBounds ?: node?.let { Rect().also(it::getBoundsInScreen) }
        pendingSendNode = null
        pendingSendBounds = null
        bypassPackage = node?.packageName?.toString().orEmpty().ifBlank { monitoredPackage }
        bypassUntil = SystemClock.uptimeMillis() + BYPASS_DURATION_MS
        monitoringActive = false
        riskActive = false
        confirmationShowing = false
        RiskStateStore.write(this, SharedRiskState(false, RiskLevel.SAFE, bypassPackage, System.currentTimeMillis()))
        removeAllOverlays()
        sendBroadcast(Intent(RiskGuardContract.ACTION_EXTERNAL_SEND_COMPLETED).apply {
            setPackage(packageName)
        })
        val clicked = node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        node?.recycle()
        if (!clicked && bounds != null && !bounds.isEmpty) dispatchTap(bounds.centerX(), bounds.centerY())
    }

    private fun dispatchTap(x: Int, y: Int) {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        dispatchGesture(gesture, null, null)
    }

    private fun removeBlocker() {
        blocker?.let { runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } }
        blocker = null
        blockerSendNode?.recycle()
        blockerSendNode = null
        blockerBounds.setEmpty()
    }

    private fun removeConfirmationOverlay() {
        confirmationView?.let {
            runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) }
        }
        confirmationView = null
        confirmationShowing = false
    }

    private fun removeAllOverlays() {
        removeBlocker()
        removeConfirmationOverlay()
    }

    private fun reportExternalSendTarget(found: Boolean) {
        if (lastExternalSendState == found) return
        lastExternalSendState = found
        sendBroadcast(Intent(RiskGuardContract.ACTION_SEND_TARGET_STATE).apply {
            setPackage(packageName)
            putExtra(RiskGuardContract.EXTRA_HAS_EXTERNAL_SEND, found)
        })
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val BYPASS_DURATION_MS = 1_500L
    }
}
