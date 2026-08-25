package com.han2.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class RiskConfirmationActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(false)
        val source = intent.getStringExtra(RiskGuardContract.EXTRA_SOURCE).orEmpty()
        val dialog = AlertDialog.Builder(this)
            .setTitle("민감정보가 감지되었습니다.")
            .setMessage("이 내용을 전송하시겠습니까?")
            .setNegativeButton("수정하기") { _, _ -> sendResult(RiskGuardContract.ACTION_CANCEL_SEND, source) }
            .setPositiveButton("그래도 전송") { _, _ -> sendResult(RiskGuardContract.ACTION_CONFIRM_SEND, source) }
            .setCancelable(false)
            .create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnDismissListener { finish() }
        dialog.show()
    }

    override fun onBackPressed() = Unit

    private fun sendResult(action: String, source: String) {
        sendBroadcast(Intent(action).apply {
            setPackage(packageName)
            putExtra(RiskGuardContract.EXTRA_SOURCE, source)
        })
        finish()
    }
}
