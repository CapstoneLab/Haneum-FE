package com.han2.app

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

class RiskConfirmationActivity : AppCompatActivity() {
    private var resolved = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(false)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })

        val source = intent.getStringExtra(RiskGuardContract.EXTRA_SOURCE).orEmpty()
        val root = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(24), dp(18), dp(24))
            setBackgroundColor(Color.argb(105, 0, 0, 0))
            isClickable = true
            isFocusable = true
            addView(createCard(source), LinearLayout.LayoutParams(
                resources.displayMetrics.widthPixels.coerceAtMost(dp(342)),
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        setContentView(root)
    }

    private fun createCard(source: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(22), dp(24), dp(22), dp(20))
        background = rounded(Color.WHITE, Color.rgb(194, 58, 67), 12)

        addView(TextView(this@RiskConfirmationActivity).apply {
            text = "민감정보가\n감지되었습니다."
            textSize = 27f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(157, 40, 49))
            setTypeface(typeface, Typeface.BOLD)
        }, matchWrap())

        addView(TextView(this@RiskConfirmationActivity).apply {
            text = "민감정보가 포함되어 있을 수\n있습니다.\n보내기 전에 내용을 다시 확인해\n주세요."
            textSize = 19f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(38, 42, 49))
            setPadding(0, dp(14), 0, dp(24))
        }, matchWrap())

        addView(LinearLayout(this@RiskConfirmationActivity).apply {
            gravity = Gravity.CENTER
            addView(actionButton("수정하기", false) {
                resolve(RiskGuardContract.ACTION_CANCEL_SEND, source)
            }, LinearLayout.LayoutParams(0, dp(64), 1f))
            addView(actionButton("그래도 전송", true) {
                resolve(RiskGuardContract.ACTION_CONFIRM_SEND, source)
            }, LinearLayout.LayoutParams(0, dp(64), 1f).apply { marginStart = dp(10) })
        }, matchWrap())
    }

    private fun actionButton(label: String, filled: Boolean, action: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 19f
        gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(if (filled) Color.WHITE else Color.rgb(157, 40, 49))
        background = rounded(
            if (filled) Color.rgb(198, 61, 70) else Color.WHITE,
            Color.rgb(210, 83, 91),
            10
        )
        setOnClickListener { action() }
    }

    private fun resolve(action: String, source: String) {
        if (resolved) return
        resolved = true
        sendBroadcast(Intent(action).apply {
            setPackage(packageName)
            putExtra(RiskGuardContract.EXTRA_SOURCE, source)
        })
        finish()
    }

    private fun rounded(fill: Int, stroke: Int, radius: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(1), stroke)
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
