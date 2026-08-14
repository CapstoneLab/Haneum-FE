package com.han2.app

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.SoundEffectConstants
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.text.InputType
import android.widget.LinearLayout
import android.widget.TextView

class NativeKeyboardService : InputMethodService() {
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private var korean = true
    private var sensitiveField = false
    private var confirmationRow: LinearLayout? = null

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val variation = (info?.inputType ?: 0) and InputType.TYPE_MASK_VARIATION
        sensitiveField = variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        if (::status.isInitialized) {
            val enabled = keyboardPreferences().getBoolean(KeyboardPreferences.PROTECTION, true)
            status.text = if (enabled) "보안 - 감지중" else "보호 기능 꺼짐"
            status.setBackgroundColor(if (enabled) Color.rgb(229, 248, 238) else Color.rgb(236, 238, 242))
        }
    }

    override fun onCreateInputView(): View {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(8), dp(6), dp(8))
            setBackgroundColor(Color.rgb(226, 229, 235))
        }
        rebuild()
        return root
    }

    override fun onEvaluateInputViewShown() = true

    private fun rebuild() {
        root.removeAllViews()
        confirmationRow = null
        status = TextView(this).apply {
            text = "보안 - 감지중"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setBackgroundColor(Color.rgb(229, 248, 238))
        }
        root.addView(status, LinearLayout.LayoutParams(-1, dp(48)))
        addRow("1234567890".map(Char::toString))
        if (korean) {
            addRow(listOf("ㅂ", "ㅈ", "ㄷ", "ㄱ", "ㅅ", "ㅛ", "ㅕ", "ㅑ", "ㅐ", "ㅔ"))
            addRow(listOf("ㅁ", "ㄴ", "ㅇ", "ㄹ", "ㅎ", "ㅗ", "ㅓ", "ㅏ", "ㅣ"))
            addRow(listOf("ㅋ", "ㅌ", "ㅊ", "ㅍ", "ㅠ", "ㅜ", "ㅡ"))
        } else {
            addRow("qwertyuiop".map(Char::toString))
            addRow("asdfghjkl".map(Char::toString))
            addRow("zxcvbnm".map(Char::toString))
        }
        addRow(listOf("한/영", "공백", "⌫", "전송"))
    }

    private fun addRow(keys: List<String>) {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER }
        keys.forEach { key ->
            row.addView(TextView(this).apply {
                text = key
                textSize = 17f
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                setBackgroundColor(Color.WHITE)
                setOnClickListener { view ->
                    val preferences = keyboardPreferences()
                    if (preferences.getBoolean(KeyboardPreferences.HAPTICS, true)) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    if (preferences.getBoolean(KeyboardPreferences.SOUND, false)) {
                        (getSystemService(AUDIO_SERVICE) as AudioManager).playSoundEffect(SoundEffectConstants.CLICK)
                    }
                    handleKey(key)
                }
            }, LinearLayout.LayoutParams(0, dp(50), if (key == "공백") 2.5f else 1f).apply {
                setMargins(dp(2), dp(2), dp(2), dp(2))
            })
        }
        root.addView(row, LinearLayout.LayoutParams(-1, dp(54)))
    }

    private fun handleKey(key: String) {
        val connection = currentInputConnection ?: return
        if (key != "전송") removeConfirmation()
        when (key) {
            "⌫" -> connection.deleteSurroundingText(1, 0)
            "공백" -> connection.commitText(" ", 1)
            "한/영" -> { korean = !korean; rebuild() }
            "전송" -> sendOrWarn(connection)
            else -> connection.commitText(key, 1)
        }
        if (key != "전송") analyze(connection)
    }

    private fun analyze(connection: InputConnection): RiskResult {
        val enabled = keyboardPreferences()
            .getBoolean(KeyboardPreferences.PROTECTION, true)
        val result = if (enabled && !sensitiveField) {
            RiskAnalyzer.analyze(connection.getTextBeforeCursor(240, 0)?.toString().orEmpty())
        } else RiskResult(RiskLevel.SAFE, "보호 기능 꺼짐", "")
        status.text = when {
            !enabled -> "보호 기능 꺼짐"
            result.level == RiskLevel.SAFE -> "보안 - 감지중"
            else -> result.title
        }
        status.setBackgroundColor(when (result.level) {
            RiskLevel.SAFE -> Color.rgb(229, 248, 238)
            RiskLevel.WARNING -> Color.rgb(255, 244, 218)
            RiskLevel.DANGER -> Color.rgb(255, 226, 228)
        })
        DetectionStore.record(this, result)
        return result
    }

    private fun sendOrWarn(connection: InputConnection) {
        val result = analyze(connection)
        if (result.level == RiskLevel.DANGER) {
            showRiskConfirmation(connection, result)
            return
        }
        removeConfirmation()
        performSend(connection)
    }

    private fun showRiskConfirmation(connection: InputConnection, result: RiskResult) {
        removeConfirmation()
        status.text = "전송 전 확인 · ${result.title}"
        status.setBackgroundColor(Color.rgb(255, 226, 228))

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(Color.rgb(255, 244, 245), Color.rgb(215, 93, 101))
        }
        panel.addView(TextView(this).apply {
            text = "왜 감지됐나요?\n${result.reason}\n정말 이 내용을 전송할까요?"
            textSize = 14f
            setTextColor(Color.rgb(128, 38, 45))
            setTypeface(typeface, Typeface.BOLD)
        })
        panel.addView(LinearLayout(this).apply {
            gravity = Gravity.END
            setPadding(0, dp(8), 0, 0)
            addView(confirmButton("수정하기", false) {
                removeConfirmation()
                status.text = "보안 - 감지중"
                status.setBackgroundColor(Color.rgb(229, 248, 238))
            })
            addView(confirmButton("그래도 전송", true) {
                removeConfirmation()
                performSend(connection)
            })
        })
        confirmationRow = panel
        root.addView(panel, 1, LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(4), 0, dp(4))
        })
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
        confirmationRow?.let { root.removeView(it) }
        confirmationRow = null
    }

    private fun performSend(connection: InputConnection) {
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
    }

    private fun rounded(fill: Int, stroke: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(10).toFloat()
        setStroke(dp(1), stroke)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    @Suppress("DEPRECATION")
    private fun keyboardPreferences() = getSharedPreferences(KeyboardPreferences.NAME, MODE_MULTI_PROCESS)
}
