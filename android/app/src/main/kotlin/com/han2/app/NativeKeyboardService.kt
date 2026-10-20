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
import android.view.MotionEvent
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
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
    private var hasExternalSendButton = false
    private var currentDraftHasText = false
    private var currentRiskLevel = RiskLevel.SAFE
    private var currentRiskPattern = ""
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
    private var keyRepeatRunnable: Runnable? = null
    private val hangulComposer = HangulComposer()
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Main.immediate)
    private val realtimeClient by lazy { RealtimeAnalysisClient() }
    private val realtimeSession = RealtimeSessionState()
    private var latestRealtimeStage: RealtimeResultStage? = null
    private val realtimeDebouncer by lazy { DraftDebouncer(serviceScope, REALTIME_DEBOUNCE_MS, ::sendRealtimeDraft) }
    private val confirmationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == RiskGuardContract.ACTION_SEND_TARGET_STATE) {
                val next = intent.getBooleanExtra(RiskGuardContract.EXTRA_HAS_EXTERNAL_SEND, false)
                if (hasExternalSendButton != next) {
                    hasExternalSendButton = next
                    if (::root.isInitialized) rebuild()
                }
                return
            }
            if (intent?.action == RiskGuardContract.ACTION_EXTERNAL_SEND_COMPLETED) {
                draftSession.sent()
                currentDraftHasText = false
                currentRiskLevel = RiskLevel.SAFE
                lastAnalyzedText = ""
                remoteAnalysis = null
                remoteAnalysisText = ""
                return
            }
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
            addAction(RiskGuardContract.ACTION_EXTERNAL_SEND_COMPLETED)
            addAction(RiskGuardContract.ACTION_SEND_TARGET_STATE)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(confirmationReceiver, filter, RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(confirmationReceiver, filter)
        serviceScope.launch { realtimeClient.results.collectLatest(::handleRealtimeResult) }
    }

    override fun onDestroy() {
        stopKeyRepeat()
        stopRealtimeSession()
        realtimeClient.close()
        serviceScope.cancel()
        runCatching { unregisterReceiver(confirmationReceiver) }
        // IME processes may be recreated when the target app changes focus. Do not clear the
        // cross-process send guard here: a visible confirmation must survive that transition.
        removeConfirmation()
        super.onDestroy()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        sensitiveField = isSensitiveInputType(info?.inputType ?: 0)
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
        latestRealtimeStage = null
        currentDraftHasText = false
        currentRiskLevel = RiskLevel.SAFE
        stopRealtimeSession()
        if (sensitiveField) {
            draftSession.sent()
            hangulComposer.reset()
        } else if (KeyBackApi.configured) {
            realtimeSession.start()
            realtimeClient.connect()
        }
        if (::status.isInitialized) {
            val enabled = keyboardPreferences().getBoolean(KeyboardPreferences.PROTECTION, true)
            applyRisk(RiskResult(RiskLevel.SAFE, "", ""))
            if (!enabled) removeConfirmation()
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        stopKeyRepeat()
        stopRealtimeSession()
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        stopKeyRepeat()
        stopRealtimeSession()
        super.onFinishInput()
    }

    override fun onCreateInputView(): View {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(1), dp(4), dp(1), dp(4))
            setBackgroundColor(Color.BLACK)
            setOnApplyWindowInsetsListener { view, insets ->
                val navigationBottom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    insets.getInsets(WindowInsets.Type.navigationBars()).bottom
                } else {
                    @Suppress("DEPRECATION")
                    insets.systemWindowInsetBottom
                }
                view.setPadding(dp(1), dp(4), dp(1), maxOf(dp(4), navigationBottom + dp(2)))
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
        addRow(("1234567890").map(Char::toString))
        if (symbols) {
            addRow(listOf("+", "×", "÷", "=", "/", "_", "<", ">", "[", "]"))
            addRow(listOf("!", "@", "#", "$", "%", "^", "&", "*", "(", ")"))
            addRow(listOf("1/2", "-", "'", "\"", ":", ";", ",", "?", "⌫"), edgeKeys = setOf("1/2", "⌫"))
            addBottomRow(listOf(
                KeySpec("ABC", 1.15f), KeySpec("한\n/영", 0.8f, "한/영"), KeySpec(",", 0.8f),
                KeySpec("⎵", 4f, "공백"), KeySpec(".", 0.8f), KeySpec(actionKeyLabel(), 1.15f, actionKeyAction())
            ))
        } else if (korean) {
            addRow((if (shifted) "ㅃㅉㄸㄲㅆㅛㅕㅑㅒㅖ" else "ㅂㅈㄷㄱㅅㅛㅕㅑㅐㅔ").map(Char::toString))
            addRow(listOf("ㅁ", "ㄴ", "ㅇ", "ㄹ", "ㅎ", "ㅗ", "ㅓ", "ㅏ", "ㅣ"), sideInset = 0.5f)
            addRow(listOf("⇧", "ㅋ", "ㅌ", "ㅊ", "ㅍ", "ㅠ", "ㅜ", "ㅡ", "⌫"), edgeKeys = setOf("⇧", "⌫"))
            addBottomRow(listOf(
                KeySpec("!#1", 1.15f), KeySpec("한\n/영", 0.8f, "한/영"), KeySpec(",", 0.8f),
                KeySpec("⎵", 4f, "공백"), KeySpec(".", 0.8f), KeySpec(actionKeyLabel(), 1.15f, actionKeyAction())
            ))
        } else {
            addRow("qwertyuiop".map(Char::toString))
            addRow("asdfghjkl".map(Char::toString), sideInset = 0.5f)
            addRow(listOf("⇧") + "zxcvbnm".map(Char::toString) + "⌫", edgeKeys = setOf("⇧", "⌫"))
            addBottomRow(listOf(
                KeySpec("!#1", 1.15f), KeySpec("한\n/영", 0.8f, "한/영"), KeySpec(",", 0.8f),
                KeySpec("⎵", 4f, "공백"), KeySpec(".", 0.8f), KeySpec(actionKeyLabel(), 1.15f, actionKeyAction())
            ))
        }
    }

    private fun actionKeyLabel(): String {
        if (!usesImeActionKey()) return "↵"
        return when (editorAction()) {
            EditorInfo.IME_ACTION_SEARCH -> "⌕"
            EditorInfo.IME_ACTION_DONE -> "✓"
            else -> "→"
        }
    }

    private fun actionKeyAction(): String = if (usesImeActionKey()) IME_ACTION_KEY else "↵"

    private fun usesImeActionKey(): Boolean = !hasExternalSendButton && editorAction() in setOf(
        EditorInfo.IME_ACTION_SEARCH,
        EditorInfo.IME_ACTION_GO,
        EditorInfo.IME_ACTION_SEND,
        EditorInfo.IME_ACTION_DONE
    )

    private fun editorAction(): Int = currentInputEditorInfo?.imeOptions
        ?.and(EditorInfo.IME_MASK_ACTION)
        ?: EditorInfo.IME_ACTION_UNSPECIFIED

    private fun addRow(keys: List<String>, sideInset: Float = 0f, edgeKeys: Set<String> = emptySet()) {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER }
        if (sideInset > 0f) row.addView(View(this), LinearLayout.LayoutParams(0, dp(52), sideInset))
        keys.forEach { key ->
            row.addView(TextView(this).apply {
                text = key
                textSize = 21f
                gravity = Gravity.CENTER
                setTypeface(Typeface.create("sans-serif", Typeface.NORMAL))
                setTextColor(Color.rgb(245, 245, 248))
                background = roundedKey(if (key in edgeKeys) Color.rgb(22, 22, 25) else Color.rgb(65, 65, 69))
                bindKeyInteraction(this, key)
            }, LinearLayout.LayoutParams(0, dp(48), if (key in edgeKeys) 1.3f else 1f).apply {
                setMargins(dp(4), dp(3), dp(4), dp(3))
            })
        }
        if (sideInset > 0f) row.addView(View(this), LinearLayout.LayoutParams(0, dp(52), sideInset))
        root.addView(row, LinearLayout.LayoutParams(-1, dp(54)))
    }

    private data class KeySpec(val label: String, val weight: Float = 1f, val action: String = label)

    private fun addBottomRow(keys: List<KeySpec>) {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER }
        keys.forEach { key ->
            row.addView(TextView(this).apply {
                text = key.label
                textSize = if (key.label.contains('\n')) 11f else if (key.label.length > 2) 16f else 22f
                gravity = Gravity.CENTER
                setTypeface(Typeface.create("sans-serif", Typeface.NORMAL))
                setTextColor(if (key.action == IME_ACTION_KEY) Color.rgb(91, 139, 255) else Color.rgb(245, 245, 248))
                background = roundedKey(if (key.action in setOf("!#1", "ABC", "한/영", "↵", IME_ACTION_KEY)) Color.rgb(22, 22, 25) else Color.rgb(65, 65, 69))
                bindKeyInteraction(this, key.action)
            }, LinearLayout.LayoutParams(0, dp(52), key.weight).apply {
                setMargins(dp(4), dp(3), dp(4), dp(3))
            })
        }
        root.addView(row, LinearLayout.LayoutParams(-1, dp(58)))
    }

    private fun bindKeyInteraction(view: View, key: String) {
        if (!isRepeatableKey(key)) {
            view.setOnClickListener {
                playKeyFeedback(view)
                handleKey(key)
            }
            return
        }
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    stopKeyRepeat()
                    playKeyFeedback(view)
                    handleKey(key)
                    keyRepeatRunnable = object : Runnable {
                        override fun run() {
                            playKeyFeedback(view)
                            handleKey(key)
                            mainHandler.postDelayed(this, KEY_REPEAT_INTERVAL_MS)
                        }
                    }.also { mainHandler.postDelayed(it, KEY_REPEAT_START_DELAY_MS) }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    stopKeyRepeat()
                    true
                }
                else -> true
            }
        }
    }

    private fun isRepeatableKey(key: String): Boolean =
        key == "⌫" || (!shifted && key.length == 1 && key != "⇧")

    private fun playKeyFeedback(view: View) {
        val preferences = keyboardPreferences()
        if (preferences.getBoolean(KeyboardPreferences.HAPTICS, true)) {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
        if (preferences.getBoolean(KeyboardPreferences.SOUND, false)) {
            (getSystemService(AUDIO_SERVICE) as AudioManager).playSoundEffect(SoundEffectConstants.CLICK)
        }
    }

    private fun stopKeyRepeat() {
        keyRepeatRunnable?.let(mainHandler::removeCallbacks)
        keyRepeatRunnable = null
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
            "!#1" -> {
                finishHangul(connection)
                symbols = true
                rebuild()
            }
            "ABC" -> {
                finishHangul(connection)
                symbols = false
                korean = false
                rebuild()
            }
            "1/2" -> Unit
            "한글" -> {
                finishHangul(connection)
                symbols = false
                korean = true
                rebuild()
            }
            "↵" -> {
                finishHangul(connection)
                connection.commitText("\n", 1)
            }
            IME_ACTION_KEY -> {
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
        if (key !in setOf("전송", IME_ACTION_KEY)) analyze(connection)
    }

    private fun analyze(connection: InputConnection): RiskResult {
        val enabled = keyboardPreferences()
            .getBoolean(KeyboardPreferences.PROTECTION, true)
        val appEnabled = activeAppPackage.isBlank() || keyboardPreferences().getBoolean(
            KeyboardPreferences.APP_SCOPE_PREFIX + activeAppPackage,
            true
        )
        val text = connection.getTextBeforeCursor(1000, 0)?.toString().orEmpty()
        currentDraftHasText = text.isNotBlank()
        currentRiskPattern = RegexPrivacyDetector().detect(text).findings
            .map { it.label }
            .distinct()
            .joinToString(", ")
        if (currentRiskPattern.isBlank() && text.isNotBlank()) {
            currentRiskPattern = when {
                text.contains("계좌", ignoreCase = true) -> "계좌번호 관련 문맥"
                text.contains("비밀번호", ignoreCase = true) ||
                    text.contains("인증번호", ignoreCase = true) ||
                    text.contains("OTP", ignoreCase = true) -> "인증정보 관련 문맥"
                else -> ""
            }
        }
        if (realtimeSession.draftChanged(text)) realtimeClient.clearPendingDraft()
        draftSession.textChanged(text)
        val result = if (enabled && appEnabled && !sensitiveField) {
            draftSession.latchRisk(RiskAnalyzer.analyze(text.takeLast(240)))
        } else RiskResult(RiskLevel.SAFE, "보호 기능 꺼짐", "")
        applyRisk(result)
        DetectionStore.record(this, result, text, activeAppPackage)
        if (shouldRequestAnalysis(enabled, appEnabled, sensitiveField, text, realtimeSession.lastSentText)) {
            scheduleRealtimeAnalysis(text, result)
        }
        return result
    }

    private fun finishHangul(connection: InputConnection) {
        if (hangulComposer.text().isNotEmpty()) connection.finishComposingText()
        hangulComposer.reset()
    }

    private fun scheduleRealtimeAnalysis(text: String, local: RiskResult) {
        if (!realtimeSession.active) return
        realtimeDebouncer.submit(text, local)
    }

    private fun sendRealtimeDraft(text: String, local: RiskResult) {
        val sessionId = realtimeSession.sessionId ?: return
        val sequence = realtimeSession.nextSequence(text) ?: return
        latestRealtimeStage = null
        realtimeClient.sendDraft(RealtimeDraft(
            sessionId = sessionId,
            sequence = sequence,
            requestId = UUID.randomUUID().toString(),
            deviceId = KeyBackApi.deviceId(this),
            appPackage = activeAppPackage,
            conversationId = activeConversationId.takeIf(String::isNotBlank),
            messageId = draftSession.messageId,
            inputText = text,
            maskedText = KeyBackApi.maskSensitive(text),
            contextMessages = emptyList(),
            localResult = local,
            clientVersion = BuildConfig.VERSION_NAME
        ))
    }

    private fun handleRealtimeResult(event: RealtimeResult) {
        if (!realtimeSession.accepts(event)) return
        if (event.stage == RealtimeResultStage.PROVISIONAL && latestRealtimeStage == RealtimeResultStage.FINAL) return
        latestRealtimeStage = event.stage
        val stageResult = if (event.stage == RealtimeResultStage.PROVISIONAL &&
            event.analysis.result.level == RiskLevel.DANGER) {
            event.analysis.result.copy(
                level = RiskLevel.WARNING,
                title = "민감정보 의심",
                reason = "민감정보 여부를 분석하고 있습니다."
            )
        } else event.analysis.result
        val resolvedResult = if (event.stage == RealtimeResultStage.FINAL) {
            draftSession.resolveFinal(stageResult)
        } else {
            draftSession.latchRisk(stageResult)
        }
        val remote = event.analysis.copy(result = resolvedResult)
        remoteAnalysis = remote
        remoteAnalysisText = realtimeSession.lastSentText
        remote.conversationRisk?.let { risk ->
            if (risk.conversationId.isBlank() || risk.conversationId == activeConversationId) {
                val merged = conversationStates.apply(activeConversationId, risk.status, event.sequence)
                conversationStore.saveStatus(activeConversationId, merged)
            }
        }
        applyRisk(remote.result)
        DetectionStore.record(this, remote.result, realtimeSession.lastSentText, activeAppPackage)
    }

    private fun stopRealtimeSession() {
        realtimeDebouncer.cancel()
        realtimeClient.disconnect()
        realtimeSession.stop()
        latestRealtimeStage = null
    }

    private fun scheduleRemoteAnalysis(text: String, local: RiskResult) {
        if (!KeyBackApi.configured || text == lastAnalyzedText) return
        lastAnalyzedText = text
        val generation = ++analysisGeneration
        val revision = draftSession.revision
        if (local.level == RiskLevel.SAFE) showCheckingStatus()
        debounceRunnable?.let(mainHandler::removeCallbacks)
        debounceRunnable = Runnable {
            if (generation == analysisGeneration) requestRemoteAnalysis(text, local, generation, revision)
        }
        val delay = if (local.level == RiskLevel.SAFE) {
            ANALYSIS_DEBOUNCE_MS
        } else {
            URGENT_ANALYSIS_DEBOUNCE_MS
        }
        mainHandler.postDelayed(debounceRunnable!!, delay)
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
                    val effectiveRemote = remote.copy(result = draftSession.resolveFinal(remote.result))
                    remoteAnalysis = effectiveRemote
                    remoteAnalysisText = text
                    remote.conversationRisk?.let { risk ->
                        if (risk.conversationId.isBlank() || risk.conversationId == conversationId) {
                            val merged = conversationStates.apply(conversationId, risk.status, revision)
                            conversationStore.saveStatus(conversationId, merged)
                        }
                    }
                    applyRisk(effectiveRemote.result)
                    DetectionStore.record(this, effectiveRemote.result, text, activeAppPackage)
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
        currentRiskLevel = result.level
        when (conversationStates.current(activeConversationId)) {
            ConversationStatus.CONFIRMED -> {
                currentRiskLevel = RiskLevel.DANGER
                showStatus(
                    "민감정보가 감지되었습니다.",
                    ConversationUi.RED_TEXT,
                    ConversationUi.RED_BACKGROUND
                )
                sendRiskState(true)
                return
            }
            ConversationStatus.WATCHING -> {
                currentRiskLevel = RiskLevel.WARNING
                showStatus(
                    "민감정보로 의심되어 분석 중입니다.",
                    Color.rgb(137, 91, 18),
                    Color.rgb(255, 244, 204)
                )
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
                "민감정보로 의심되어 분석 중입니다.",
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
        RiskStateStore.write(this, SharedRiskState(
            hasDraft = currentDraftHasText,
            level = currentRiskLevel,
            appPackage = activeAppPackage,
            updatedAt = System.currentTimeMillis(),
            pattern = currentRiskPattern,
            inputPreview = KeyBackApi.maskSensitive(
                currentInputConnection?.getTextBeforeCursor(1000, 0)?.toString().orEmpty()
            )
        ))
        sendBroadcast(Intent(RiskGuardContract.ACTION_RISK_STATE).apply {
            setPackage(packageName)
            putExtra(RiskGuardContract.EXTRA_DANGER, currentRiskLevel != RiskLevel.SAFE)
            putExtra(RiskGuardContract.EXTRA_HAS_DRAFT, currentDraftHasText)
            putExtra(RiskGuardContract.EXTRA_RISK_LEVEL, currentRiskLevel.name)
            putExtra(RiskGuardContract.EXTRA_RISK_PATTERN, currentRiskPattern)
            putExtra(
                RiskGuardContract.EXTRA_INPUT_PREVIEW,
                KeyBackApi.maskSensitive(currentInputConnection?.getTextBeforeCursor(1000, 0)?.toString().orEmpty())
            )
            putExtra(RiskGuardContract.EXTRA_APP_PACKAGE, activeAppPackage)
            putExtra(RiskGuardContract.EXTRA_DRAFT_ID, draftSession.messageId)
        })
    }

    private fun sendOrWarn(connection: InputConnection) {
        val local = analyze(connection)
        val text = connection.getTextBeforeCursor(1000, 0)?.toString().orEmpty()
        debounceRunnable?.let(mainHandler::removeCallbacks)
        debounceRunnable = null
        val remote = remoteAnalysis?.takeIf { remoteAnalysisText == text }
        val result = remote?.result ?: local
        if (result.level != RiskLevel.SAFE) {
            DetectionStore.recordSendAttempt(this, result, currentRiskPattern, text, activeAppPackage)
        }
        if (conversationStates.current(activeConversationId) == ConversationStatus.CONFIRMED ||
            result.level == RiskLevel.DANGER) {
            showRiskConfirmation(connection, result)
        } else {
            removeConfirmation()
            remote?.let { KeyBackApi.saveAction(this, it.requestId, "continue") }
            performSend(connection)
        }
    }

    private fun showRiskConfirmation(connection: InputConnection, result: RiskResult) {
        if (pendingKeyboardSend) return
        pendingKeyboardSend = true
        sendBroadcast(Intent(RiskGuardContract.ACTION_REQUEST_CONFIRMATION).apply {
            setPackage(packageName)
            putExtra(RiskGuardContract.EXTRA_SOURCE, RiskGuardContract.SOURCE_KEYBOARD)
            putExtra(RiskGuardContract.EXTRA_APP_PACKAGE, activeAppPackage)
            putExtra(RiskGuardContract.EXTRA_DRAFT_ID, draftSession.messageId)
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
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
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
        return PopupWindow(content, dp(340), LinearLayout.LayoutParams.WRAP_CONTENT, false).apply {
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
            setPadding(dp(24), dp(26), dp(24), dp(22))
            background = rounded(Color.WHITE, Color.rgb(221, 87, 96))
            addView(TextView(this@NativeKeyboardService).apply {
                text = "민감정보가 감지되었습니다."
                textSize = 27f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(157, 40, 49))
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 0, 0, dp(12))
            }, LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(TextView(this@NativeKeyboardService).apply {
                text = "민감정보가 포함되어 있을 수 있습니다.\n보내기 전에 내용을 다시 확인해 주세요."
                textSize = 21f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(45, 49, 57))
                setPadding(0, 0, 0, dp(22))
            }, LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(LinearLayout(this@NativeKeyboardService).apply {
                gravity = Gravity.CENTER
                addView(confirmButton(primaryLabel, false) { onPrimary() },
                    LinearLayout.LayoutParams(0, dp(64), 1f))
                if (secondaryLabel != null && onSecondary != null) {
                    addView(confirmButton(secondaryLabel, true) { onSecondary() },
                        LinearLayout.LayoutParams(0, dp(64), 1f).apply { marginStart = dp(10) })
                }
            }, LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT))
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
        textSize = 20f
        gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(if (danger) Color.WHITE else Color.rgb(135, 48, 55))
        background = rounded(if (danger) Color.rgb(190, 58, 67) else Color.WHITE, Color.rgb(210, 104, 111))
        setPadding(dp(10), dp(12), dp(10), dp(12))
        setOnClickListener { action() }
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
        realtimeDebouncer.cancel()
        realtimeSession.draftChanged("")
        realtimeClient.clearPendingDraft()
        val sentText = connection.getTextBeforeCursor(1000, 0)?.toString().orEmpty()
        if (sentText.isNotBlank() && activeConversationId.isNotBlank()) {
            conversationStore.append(activeConversationId, ContextMessage("user", KeyBackApi.maskSensitive(sentText)))
        }
        currentDraftHasText = false
        currentRiskLevel = RiskLevel.SAFE
        currentRiskPattern = ""
        sendRiskState(false)
        val editorAction = currentInputEditorInfo?.imeOptions
            ?.and(EditorInfo.IME_MASK_ACTION)
            ?: EditorInfo.IME_ACTION_UNSPECIFIED
        val actionHandled = editorAction != EditorInfo.IME_ACTION_UNSPECIFIED &&
            editorAction != EditorInfo.IME_ACTION_NONE &&
            connection.performEditorAction(editorAction)
        if (!actionHandled) {
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
        draftSession.sent()
        lastAnalyzedText = ""
        remoteAnalysis = null
        remoteAnalysisText = ""
    }

    companion object {
        private const val REALTIME_DEBOUNCE_MS = 300L
        private const val ANALYSIS_DEBOUNCE_MS = 250L
        private const val URGENT_ANALYSIS_DEBOUNCE_MS = 50L
        private const val KEY_REPEAT_START_DELAY_MS = 450L
        private const val KEY_REPEAT_INTERVAL_MS = 70L
        private const val IME_ACTION_KEY = "IME_ACTION"
    }

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

internal fun isSensitiveInputType(inputType: Int): Boolean {
    val variation = inputType and InputType.TYPE_MASK_VARIATION
    val typeClass = inputType and InputType.TYPE_MASK_CLASS
    return (typeClass == InputType.TYPE_CLASS_TEXT && variation in setOf(
        InputType.TYPE_TEXT_VARIATION_PASSWORD,
        InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
        InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
    )) || (typeClass == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
}
