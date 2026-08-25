package com.han2.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ColorDrawable
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.SoundEffectConstants
import android.view.View
import android.view.WindowManager
import android.view.WindowInsets
import android.graphics.PixelFormat
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.text.InputType
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import java.util.UUID

class NativeKeyboardService : InputMethodService() {
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private var korean = true
    private var shifted = false
    private var symbols = false
    private var sensitiveField = false
    private var activeWarningPopup: PopupWindow? = null
    private var activeOverlayView: View? = null
    private var dangerNotified = false
    private var activeAppPackage = ""
    private var pendingKeyboardSend = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var analysisGeneration = 0
    private var lastAnalyzedText = ""
    private var remoteAnalysis: RemoteAnalysis? = null
    private var remoteAnalysisText = ""
    private val conversationStore by lazy { ConversationStore(this) }
    private val conversationStates = ConversationStateMachine()
    private val draftSession = DraftSession()
    private var imeSessionId = UUID.randomUUID().toString()
    private var activeConversationId = ""
    private var debounceRunnable: Runnable? = null
    private val hangulComposer = HangulComposer()
    private val confirmationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.getStringExtra(RiskGuardContract.EXTRA_SOURCE) != RiskGuardContract.SOURCE_KEYBOARD) return
            when (intent.action) {
                RiskGuardContract.ACTION_CONFIRM_SEND -> {
                    pendingKeyboardSend = false
                    remoteAnalysis?.let { KeyBackApi.saveAction(this@NativeKeyboardService, it.requestId, "continue") }
                    currentInputConnection?.let(::performSend)
                }
                RiskGuardContract.ACTION_CANCEL_SEND -> {
                    pendingKeyboardSend = false
                    remoteAnalysis?.let { KeyBackApi.saveAction(this@NativeKeyboardService, it.requestId, "edit") }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter().apply {
            addAction(RiskGuardContract.ACTION_CONFIRM_SEND)
            addAction(RiskGuardContract.ACTION_CANCEL_SEND)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(confirmationReceiver, filter, RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(confirmationReceiver, filter)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(confirmationReceiver) }
        sendRiskState(false)
        removeConfirmation()
        super.onDestroy()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val variation = (info?.inputType ?: 0) and InputType.TYPE_MASK_VARIATION
        sensitiveField = variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        activeAppPackage = info?.packageName.orEmpty()
        if (!restarting || activeConversationId.isBlank()) imeSessionId = UUID.randomUUID().toString()
        val editorIdentity = info?.privateImeOptions?.takeIf { it.isNotBlank() }
        activeConversationId = ConversationIdentity.create(activeAppPackage, editorIdentity, imeSessionId)
        draftSession.switchConversation(activeConversationId)
        conversationStates.restore(activeConversationId, conversationStore.status(activeConversationId))
        analysisGeneration++
        debounceRunnable?.let(mainHandler::removeCallbacks)
        debounceRunnable = null
        lastAnalyzedText = ""
        remoteAnalysis = null
        remoteAnalysisText = ""
        if (::status.isInitialized) {
            val enabled = keyboardPreferences().getBoolean(KeyboardPreferences.PROTECTION, true)
            applyRisk(RiskResult(RiskLevel.SAFE, "", ""))
            if (!enabled) removeConfirmation()
        }
    }

    override fun onCreateInputView(): View {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(8), dp(6), dp(8))
            setBackgroundColor(Color.rgb(226, 229, 235))
            setOnApplyWindowInsetsListener { view, insets ->
                val navigationBottom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    insets.getInsets(WindowInsets.Type.navigationBars()).bottom
                } else {
                    @Suppress("DEPRECATION")
                    insets.systemWindowInsetBottom
                }
                view.setPadding(dp(6), dp(8), dp(6), maxOf(dp(10), navigationBottom + dp(6)))
                insets
            }
        }
        rebuild()
        return root
    }

    override fun onEvaluateInputViewShown() = true

    private fun rebuild() {
        root.removeAllViews()
        status = TextView(this).apply {
            visibility = View.GONE
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setBackgroundColor(Color.rgb(229, 248, 238))
        }
        root.addView(status, LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT))
        if (symbols) {
            addRow(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"))
            addRow(listOf("@", "#", "₩", "_", "&", "-", "+", "(", ")", "/"))
            addRow(listOf("*", "\"", "'", ":", ";", "!", "?", "⌫"), edgeKeys = setOf("⌫"))
            addBottomRow(listOf(KeySpec("한글", 1.35f), KeySpec(","), KeySpec("공백", 4f), KeySpec("."), KeySpec("↵", 1.35f)))
        } else if (korean) {
            addRow((if (shifted) "ㅃㅉㄸㄲㅆㅛㅕㅑㅒㅖ" else "ㅂㅈㄷㄱㅅㅛㅕㅑㅐㅔ").map(Char::toString))
            addRow(listOf("ㅁ", "ㄴ", "ㅇ", "ㄹ", "ㅎ", "ㅗ", "ㅓ", "ㅏ", "ㅣ"), sideInset = 0.5f)
            addRow(listOf("⇧", "ㅋ", "ㅌ", "ㅊ", "ㅍ", "ㅠ", "ㅜ", "ㅡ", "⌫"), edgeKeys = setOf("⇧", "⌫"))
            addBottomRow(listOf(KeySpec("?123", 1.25f), KeySpec(","), KeySpec("한/영", 1.15f), KeySpec("공백", 3.5f), KeySpec("."), KeySpec("↵", 1.25f)))
        } else {
            addRow("qwertyuiop".map(Char::toString))
            addRow("asdfghjkl".map(Char::toString), sideInset = 0.5f)
            addRow(listOf("⇧") + "zxcvbnm".map(Char::toString) + "⌫", edgeKeys = setOf("⇧", "⌫"))
            addBottomRow(listOf(KeySpec("?123", 1.25f), KeySpec(","), KeySpec("한/영", 1.15f), KeySpec("공백", 3.5f), KeySpec("."), KeySpec("↵", 1.25f)))
        }
    }

    private fun addRow(keys: List<String>, sideInset: Float = 0f, edgeKeys: Set<String> = emptySet()) {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER }
        if (sideInset > 0f) row.addView(View(this), LinearLayout.LayoutParams(0, dp(52), sideInset))
        keys.forEach { key ->
            row.addView(TextView(this).apply {
                text = key
                textSize = 17f
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.rgb(45, 49, 57))
                background = roundedKey(if (key in edgeKeys) Color.rgb(205, 210, 220) else Color.WHITE)
                setOnClickListener { view ->
                    val preferences = keyboardPreferences()
                    if (preferences.getBoolean(KeyboardPreferences.HAPTICS, true)) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    if (preferences.getBoolean(KeyboardPreferences.SOUND, false)) {
                        (getSystemService(AUDIO_SERVICE) as AudioManager).playSoundEffect(SoundEffectConstants.CLICK)
                    }
                    handleKey(key)
                }
            }, LinearLayout.LayoutParams(0, dp(50), if (key in edgeKeys) 1.25f else 1f).apply {
                setMargins(dp(2), dp(2), dp(2), dp(2))
            })
        }
        if (sideInset > 0f) row.addView(View(this), LinearLayout.LayoutParams(0, dp(52), sideInset))
        root.addView(row, LinearLayout.LayoutParams(-1, dp(54)))
    }

    private data class KeySpec(val label: String, val weight: Float = 1f)

    private fun addBottomRow(keys: List<KeySpec>) {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER }
        keys.forEach { key ->
            row.addView(TextView(this).apply {
                text = key.label
                textSize = if (key.label.length > 2) 13f else 17f
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.rgb(45, 49, 57))
                background = roundedKey(if (key.label in setOf("?123", "한글", "↵")) Color.rgb(205, 210, 220) else Color.WHITE)
                setOnClickListener { handleKey(key.label) }
            }, LinearLayout.LayoutParams(0, dp(52), key.weight).apply {
                setMargins(dp(2), dp(2), dp(2), dp(2))
            })
        }
        root.addView(row, LinearLayout.LayoutParams(-1, dp(56)))
    }

    private fun handleKey(key: String) {
        val connection = currentInputConnection ?: return
        if (key != "↵") removeConfirmation()
        when (key) {
            "⌫" -> {
                if (korean && hangulComposer.backspace()) {
                    connection.setComposingText(hangulComposer.text(), 1)
                } else connection.deleteSurroundingText(1, 0)
            }
            "공백" -> {
                finishHangul(connection)
                connection.commitText(" ", 1)
            }
            "한/영" -> {
                finishHangul(connection)
                korean = !korean
                rebuild()
            }
            "⇧" -> {
                shifted = !shifted
                rebuild()
            }
            "?123" -> {
                finishHangul(connection)
                symbols = true
                rebuild()
            }
            "한글" -> {
                finishHangul(connection)
                symbols = false
                korean = true
                rebuild()
            }
            "↵" -> {
                finishHangul(connection)
                sendOrWarn(connection)
            }
            else -> {
                if (korean && !symbols && key.length == 1 && (key[0] in 'ㄱ'..'ㅎ' || key[0] in 'ㅏ'..'ㅣ')) {
                    connection.setComposingText(hangulComposer.input(key[0]), 1)
                    if (shifted) { shifted = false; rebuild() }
                } else {
                    finishHangul(connection)
                    connection.commitText(key, 1)
                }
            }
        }
        if (key != "전송") analyze(connection)
    }

    private fun analyze(connection: InputConnection): RiskResult {
        val enabled = keyboardPreferences()
            .getBoolean(KeyboardPreferences.PROTECTION, true)
        val appEnabled = activeAppPackage.isBlank() || keyboardPreferences().getBoolean(
            KeyboardPreferences.APP_SCOPE_PREFIX + activeAppPackage,
            true
        )
        val text = connection.getTextBeforeCursor(1000, 0)?.toString().orEmpty()
        draftSession.textChanged(text)
        val result = if (enabled && appEnabled && !sensitiveField) {
            draftSession.latchDanger(RiskAnalyzer.analyze(text.takeLast(240)))
        } else RiskResult(RiskLevel.SAFE, "보호 기능 꺼짐", "")
        applyRisk(result)
        DetectionStore.record(this, result)
        if (shouldRequestAnalysis(enabled, appEnabled, sensitiveField, text, lastAnalyzedText)) {
            scheduleRemoteAnalysis(text, result)
        }
        return result
    }

    private fun finishHangul(connection: InputConnection) {
        if (hangulComposer.text().isNotEmpty()) connection.finishComposingText()
        hangulComposer.reset()
    }

    private fun scheduleRemoteAnalysis(text: String, local: RiskResult) {
        if (!KeyBackApi.configured || text == lastAnalyzedText) return
        lastAnalyzedText = text
        val generation = ++analysisGeneration
        val revision = draftSession.revision
        if (local.level != RiskLevel.DANGER) showCheckingStatus()
        debounceRunnable?.let(mainHandler::removeCallbacks)
        debounceRunnable = Runnable {
            if (generation == analysisGeneration) requestRemoteAnalysis(text, local, generation, revision)
        }
        mainHandler.postDelayed(debounceRunnable!!, ANALYSIS_DEBOUNCE_MS)
    }

    private fun requestRemoteAnalysis(
        text: String,
        local: RiskResult,
        generation: Int,
        revision: Long,
        completion: ((RemoteAnalysis?) -> Unit)? = null
    ) {
        val conversationId = activeConversationId
        val messageId = draftSession.messageId
        val input = AnalyzeInput(
            appPackage = activeAppPackage,
            conversationId = conversationId,
            messageId = messageId,
            text = text,
            contextMessages = conversationStore.recentMessages(conversationId),
            local = local
        )
        KeyBackApi.analyze(this, input) { response ->
            mainHandler.post {
                val stillCurrent = generation == analysisGeneration &&
                    revision == draftSession.revision &&
                    conversationId == activeConversationId &&
                    messageId == draftSession.messageId
                if (!stillCurrent) return@post
                response.onSuccess { remote ->
                    val effectiveRemote = remote.copy(result = draftSession.latchDanger(remote.result))
                    remoteAnalysis = effectiveRemote
                    remoteAnalysisText = text
                    remote.conversationRisk?.let { risk ->
                        if (risk.conversationId.isBlank() || risk.conversationId == conversationId) {
                            val merged = conversationStates.apply(conversationId, risk.status, revision)
                            conversationStore.saveStatus(conversationId, merged)
                        }
                    }
                    applyRisk(effectiveRemote.result)
                    DetectionStore.record(this, effectiveRemote.result)
                    completion?.invoke(effectiveRemote)
                }.onFailure {
                    // Preserve the last known conversation state on timeout, cancellation, or failure.
                    applyRisk(local)
                    completion?.invoke(null)
                }
            }
        }
    }

    private fun applyRisk(result: RiskResult) {
        if (!::status.isInitialized) return
        when (conversationStates.current(activeConversationId)) {
            ConversationStatus.CONFIRMED -> {
                val ui = ConversationUi.presentation(ConversationStatus.CONFIRMED)
                showStatus(ui.message!!, ui.foreground, ui.background)
                sendRiskState(true)
                return
            }
            ConversationStatus.WATCHING -> {
                val ui = ConversationUi.presentation(ConversationStatus.WATCHING)
                showStatus(ui.message!!, ui.foreground, ui.background)
                sendRiskState(false)
                return
            }
            ConversationStatus.NORMAL -> Unit
        }
        val danger = result.level == RiskLevel.DANGER
        when (result.level) {
            RiskLevel.DANGER -> showStatus(
                "민감정보가 감지되었습니다.",
                Color.rgb(157, 40, 49),
                Color.rgb(255, 226, 228)
            )
            RiskLevel.WARNING -> showStatus(
                "민감정보로 의심되는 내용을 확인 중입니다.",
                Color.rgb(137, 91, 18),
                Color.rgb(255, 244, 204)
            )
            RiskLevel.SAFE -> status.visibility = View.GONE
        }
        sendRiskState(danger)
    }

    private fun showCheckingStatus() = showStatus(
        "민감정보 여부를 확인하고 있습니다.",
        Color.rgb(88, 96, 112),
        Color.rgb(238, 241, 247)
    )

    private fun showStatus(message: String, textColor: Int, backgroundColor: Int) {
        status.visibility = View.VISIBLE
        status.text = message
        status.textSize = 17f
        status.setTextColor(textColor)
        status.setBackgroundColor(backgroundColor)
    }

    private fun sendRiskState(danger: Boolean) {
        sendBroadcast(Intent(RiskGuardContract.ACTION_RISK_STATE).apply {
            setPackage(packageName)
            putExtra(RiskGuardContract.EXTRA_DANGER, danger)
        })
    }

    private fun sendOrWarn(connection: InputConnection) {
        val local = analyze(connection)
        val text = connection.getTextBeforeCursor(1000, 0)?.toString().orEmpty()
        debounceRunnable?.let(mainHandler::removeCallbacks)
        debounceRunnable = null
        val decide: (RemoteAnalysis?) -> Unit = { remote ->
            val result = remote?.result
                ?: remoteAnalysis?.takeIf { remoteAnalysisText == text }?.result
                ?: local
            if (conversationStates.current(activeConversationId) == ConversationStatus.CONFIRMED ||
                result.level == RiskLevel.DANGER) {
                showRiskConfirmation(connection, result)
            } else {
                removeConfirmation()
                remote?.let { KeyBackApi.saveAction(this, it.requestId, "continue") }
                performSend(connection)
            }
        }
        if (!KeyBackApi.configured || sensitiveField || !isMeaningfulForRemoteAnalysis(text)) {
            decide(null)
            return
        }
        val generation = ++analysisGeneration
        requestRemoteAnalysis(text, local, generation, draftSession.revision, decide)
    }

    private fun showRiskConfirmation(connection: InputConnection, result: RiskResult) {
        if (pendingKeyboardSend) return
        pendingKeyboardSend = true
        startActivity(Intent(this, RiskConfirmationActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(RiskGuardContract.EXTRA_SOURCE, RiskGuardContract.SOURCE_KEYBOARD)
        })
    }

    private fun showWarning(
        primaryLabel: String,
        secondaryLabel: String? = null,
        onPrimary: () -> Unit,
        onSecondary: (() -> Unit)? = null
    ) {
        removeConfirmation()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && android.provider.Settings.canDrawOverlays(this)) {
            val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            val scrim = LinearLayout(this).apply {
                gravity = Gravity.CENTER
                setBackgroundColor(Color.argb(100, 0, 0, 0))
                isClickable = true
                isFocusable = true
                addView(createWarningContent(primaryLabel, secondaryLabel, onPrimary, onSecondary),
                    LinearLayout.LayoutParams(dp(340), LinearLayout.LayoutParams.WRAP_CONTENT))
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.CENTER }
            activeOverlayView = scrim
            windowManager.addView(scrim, params)
        } else {
            activeWarningPopup = createWarningPopup(primaryLabel, secondaryLabel, onPrimary, onSecondary)
                .also { showPopup(it) }
        }
    }

    private fun createWarningPopup(
        primaryLabel: String,
        secondaryLabel: String? = null,
        onPrimary: () -> Unit,
        onSecondary: (() -> Unit)? = null
    ): PopupWindow {
        val content = createWarningContent(primaryLabel, secondaryLabel, onPrimary, onSecondary)
        return PopupWindow(content, dp(340), LinearLayout.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = false
            isClippingEnabled = false
            elevation = dp(12).toFloat()
        }
    }

    private fun createWarningContent(
        primaryLabel: String,
        secondaryLabel: String?,
        onPrimary: () -> Unit,
        onSecondary: (() -> Unit)?
    ): View = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(22), dp(22), dp(22), dp(18))
            background = rounded(Color.WHITE, Color.rgb(221, 87, 96))
            addView(TextView(this@NativeKeyboardService).apply {
                text = "민감정보가 감지되었습니다."
                textSize = 22f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(157, 40, 49))
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 0, 0, dp(18))
            }, LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(LinearLayout(this@NativeKeyboardService).apply {
                gravity = Gravity.CENTER
                addView(confirmButton(primaryLabel, false) { onPrimary() })
                if (secondaryLabel != null && onSecondary != null) {
                    addView(confirmButton(secondaryLabel, true) { onSecondary() })
                }
            })
        }

    private fun showPopup(popup: PopupWindow) {
        root.post {
            if (::root.isInitialized && root.windowToken != null) {
                popup.showAtLocation(root, Gravity.CENTER, 0, 0)
            }
        }
    }

    private fun confirmButton(label: String, danger: Boolean, action: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 13f
        gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(if (danger) Color.WHITE else Color.rgb(135, 48, 55))
        background = rounded(if (danger) Color.rgb(190, 58, 67) else Color.WHITE, Color.rgb(210, 104, 111))
        setPadding(dp(14), dp(9), dp(14), dp(9))
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(8)
        }
    }

    private fun removeConfirmation() {
        activeWarningPopup?.dismiss()
        activeWarningPopup = null
        activeOverlayView?.let { view ->
            runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view) }
        }
        activeOverlayView = null
    }

    private fun performSend(connection: InputConnection) {
        val sentText = connection.getTextBeforeCursor(1000, 0)?.toString().orEmpty()
        if (sentText.isNotBlank() && activeConversationId.isNotBlank()) {
            conversationStore.append(activeConversationId, ContextMessage("user", KeyBackApi.maskSensitive(sentText)))
        }
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        draftSession.sent()
        lastAnalyzedText = ""
        remoteAnalysis = null
        remoteAnalysisText = ""
    }

    companion object { private const val ANALYSIS_DEBOUNCE_MS = 1_000L }

    private fun rounded(fill: Int, stroke: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(10).toFloat()
        setStroke(dp(1), stroke)
    }

    private fun roundedKey(fill: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(6).toFloat()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    @Suppress("DEPRECATION")
    private fun keyboardPreferences() = getSharedPreferences(KeyboardPreferences.NAME, MODE_MULTI_PROCESS)
}
