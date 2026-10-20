package com.han2.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class DetectionReport(
    val detectedAt: Long,
    val level: RiskLevel,
    val title: String,
    val reason: String,
    val pattern: String = "분류되지 않은 위험 패턴",
    val sendAttempt: Boolean = false,
    val inputPreview: String = "",
    val appPackage: String = ""
)

object DetectionStore {
    private const val NAME = "han2_detection_summary"

    fun record(context: Context, result: RiskResult, inputText: String = "", appPackage: String = "") {
        if (result.level == RiskLevel.SAFE) return
        append(context, result, "입력 중 감지", false, inputText, appPackage)
    }

    fun recordSendAttempt(
        context: Context,
        result: RiskResult,
        pattern: String,
        inputText: String = "",
        appPackage: String = ""
    ) {
        if (result.level == RiskLevel.SAFE) return
        append(context, result, pattern.ifBlank { "분류되지 않은 위험 패턴" }, true, inputText, appPackage)
    }

    private fun append(
        context: Context,
        result: RiskResult,
        pattern: String,
        sendAttempt: Boolean,
        inputText: String,
        appPackage: String
    ) {
        val preferences = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        val signature = "${result.level}:${result.title}:$pattern:$sendAttempt"
        val now = System.currentTimeMillis()
        if (preferences.getString("last_signature", "") == signature &&
            now - preferences.getLong("last_detected_at", 0L) < 30_000L
        ) return
        preferences.edit()
            .putString("last_signature", signature)
            .putString("last_level", result.level.name)
            .putString("last_title", result.title)
            .putLong("last_detected_at", now)
            .putInt("total_count", preferences.getInt("total_count", 0) + 1)
            .apply()

        val reports = try {
            JSONArray(preferences.getString("reports", "[]"))
        } catch (_: Exception) {
            JSONArray()
        }
        reports.put(JSONObject().apply {
            put("detectedAt", now)
            put("level", result.level.name)
            put("title", result.title)
            put("reason", result.reason)
            put("pattern", pattern)
            put("sendAttempt", sendAttempt)
            put("inputPreview", KeyBackApi.maskSensitive(inputText).take(500))
            put("appPackage", appPackage)
        })
        val trimmed = JSONArray()
        val start = maxOf(0, reports.length() - 50)
        for (index in start until reports.length()) trimmed.put(reports.getJSONObject(index))
        preferences.edit().putString("reports", trimmed.toString()).apply()
    }

    fun load(context: Context): List<DetectionReport> {
        val raw = context.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString("reports", "[]")
        val reports = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
        return (reports.length() - 1 downTo 0).mapNotNull { index ->
            try {
                val item = reports.getJSONObject(index)
                DetectionReport(
                    detectedAt = item.getLong("detectedAt"),
                    level = RiskLevel.valueOf(item.getString("level")),
                    title = item.getString("title"),
                    reason = item.getString("reason"),
                    pattern = item.optString("pattern", "분류되지 않은 위험 패턴"),
                    sendAttempt = item.optBoolean("sendAttempt", false),
                    inputPreview = item.optString("inputPreview"),
                    appPackage = item.optString("appPackage")
                )
            } catch (_: Exception) { null }
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
