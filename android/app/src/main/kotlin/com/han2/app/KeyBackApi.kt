package com.han2.app

import android.content.Context
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors

data class RemoteAnalysis(
    val requestId: String,
    val result: RiskResult,
    val riskScore: Int,
    val recommendedAction: String,
    val conversationRisk: ConversationRisk? = null
)

data class RemoteRiskLog(
    val id: Long,
    val createdAt: String,
    val level: RiskLevel,
    val reason: String,
    val riskScore: Int
)

data class RemoteSettings(
    val riskSensitivity: String,
    val vibrationEnabled: Boolean,
    val ttsEnabled: Boolean,
    val largeTextEnabled: Boolean,
    val highContrastEnabled: Boolean
)

object KeyBackApi {
    private val executor = Executors.newCachedThreadPool()

    val configured: Boolean get() = BuildConfig.KEYBACK_API_KEY.isNotBlank()

    fun deviceId(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?.takeIf { it.isNotBlank() } ?: "android-unknown"

    fun analyze(
        context: Context,
        input: AnalyzeInput,
        callback: (Result<RemoteAnalysis>) -> Unit
    ) = async(callback) {
        checkConfigured()
        val requestId = UUID.randomUUID().toString()
        val body = buildAnalyzeRequest(context, requestId, input)
        parseAnalysisResponse(request("POST", "/analyze", body, requestId), requestId)
    }

    internal fun buildAnalyzeRequest(context: Context, requestId: String, input: AnalyzeInput) =
        buildAnalyzeRequest(requestId, deviceId(context), BuildConfig.VERSION_NAME, input)

    internal fun buildAnalyzeRequest(
        requestId: String,
        deviceId: String,
        clientVersion: String,
        input: AnalyzeInput
    ) = JSONObject().apply {
        put("request_id", requestId)
        put("device_id", deviceId)
        put("app_package", input.appPackage)
        put("conversation_id", input.conversationId ?: JSONObject.NULL)
        put("message_id", input.messageId ?: JSONObject.NULL)
        put("input_text", input.text.take(1000))
        put("masked_text", maskSensitive(input.text).take(1000))
        put("context_messages", JSONArray().apply {
            var total = 0
            input.contextMessages.takeLast(5).forEach { message ->
                val masked = maskSensitive(message.text.take(300))
                if (total + masked.length <= 1000) {
                    put(JSONObject().put("speaker", safeSpeaker(message.speaker)).put("text", masked))
                    total += masked.length
                }
            }
        })
        put("local_detection", JSONObject().apply {
            put("detected", input.local.level != RiskLevel.SAFE)
            put("risk_types", JSONArray())
            put("local_score", when (input.local.level) {
                RiskLevel.SAFE -> 0
                RiskLevel.WARNING -> 55
                RiskLevel.DANGER -> 90
            })
        })
        put("client_timestamp", Instant.now().toString())
        put("client_version", clientVersion)
    }

    internal fun parseAnalysisResponse(response: JSONObject, fallbackRequestId: String): RemoteAnalysis {
        val data = response.getJSONObject("data")
        return parseAnalysisData(data, fallbackRequestId)
    }

    internal fun parseAnalysisData(data: JSONObject, fallbackRequestId: String): RemoteAnalysis {
        val level = riskLevel(data.optString("risk_level"))
        val conversation = data.optJSONObject("conversation_risk")?.let { risk ->
            ConversationRisk(
                conversationId = risk.optString("conversation_id"),
                status = ConversationStatus.fromWire(risk.optString("status")),
                previousStatus = ConversationStatus.fromWire(risk.optString("previous_status")),
                changed = risk.optBoolean("changed", false),
                latched = risk.optBoolean("latched", false),
                suspicionCount = risk.optInt("suspicion_count", 0),
                peakScore = risk.optInt("peak_score", data.optInt("risk_score", 0))
            )
        }
        return RemoteAnalysis(
            requestId = data.optString("request_id", fallbackRequestId),
            result = RiskResult(level, titleFor(level), data.optString("reason")),
            riskScore = data.optInt("risk_score", 0),
            recommendedAction = data.optString("recommended_action"),
            conversationRisk = conversation
        )
    }

    fun saveAction(
        context: Context,
        requestId: String,
        action: String,
        callback: (Result<Unit>) -> Unit = {}
    ) = async(callback) {
        checkConfigured()
        request("POST", "/actions", JSONObject().apply {
            put("request_id", requestId)
            put("device_id", deviceId(context))
            put("user_action", action)
            put("action_timestamp", Instant.now().toString())
        })
        Unit
    }

    fun getRiskLogs(context: Context, callback: (Result<List<RemoteRiskLog>>) -> Unit) = async(callback) {
        checkConfigured()
        val data = request("GET", "/risk-logs?device_id=${encode(deviceId(context))}&limit=100")
            .getJSONObject("data")
        val items = data.getJSONArray("items")
        (0 until items.length()).map { index ->
            val item = items.getJSONObject(index)
            RemoteRiskLog(
                id = item.getLong("id"),
                createdAt = item.getString("created_at"),
                level = riskLevel(item.getString("risk_level")),
                reason = item.optString("reason"),
                riskScore = item.getInt("risk_score")
            )
        }
    }

    fun deleteRiskLog(context: Context, logId: Long, callback: (Result<Unit>) -> Unit) = async(callback) {
        checkConfigured()
        request("DELETE", "/risk-logs/$logId?device_id=${encode(deviceId(context))}")
        Unit
    }

    fun getSettings(context: Context, callback: (Result<RemoteSettings>) -> Unit) = async(callback) {
        checkConfigured()
        parseSettings(request("GET", "/settings/${encode(deviceId(context))}").getJSONObject("data"))
    }

    fun updateSettings(context: Context, callback: (Result<RemoteSettings>) -> Unit = {}) {
        val preferences = context.getSharedPreferences(KeyboardPreferences.NAME, Context.MODE_MULTI_PROCESS)
        val body = JSONObject().apply {
            put("risk_sensitivity", preferences.getString(KeyboardPreferences.RISK_SENSITIVITY, "normal"))
            put("tts_enabled", preferences.getBoolean(KeyboardPreferences.TTS, false))
            put("vibration_enabled", preferences.getBoolean(KeyboardPreferences.HAPTICS, true))
            put("large_text_enabled", preferences.getBoolean(KeyboardPreferences.LARGE_TEXT, false))
            put("high_contrast_enabled", preferences.getBoolean(KeyboardPreferences.HIGH_CONTRAST, false))
        }
        async(callback) {
            checkConfigured()
            parseSettings(request("PUT", "/settings/${encode(deviceId(context))}", body).getJSONObject("data"))
        }
    }

    fun health(callback: (Result<String>) -> Unit) = async(callback) {
        checkConfigured()
        request("GET", "/health").getJSONObject("data").getString("status")
    }

    private fun request(
        method: String,
        path: String,
        body: JSONObject? = null,
        requestId: String? = null
    ): JSONObject {
        val connection = URI(BuildConfig.KEYBACK_BASE_URL.trimEnd('/') + path).toURL()
            .openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.connectTimeout = 7_000
            connection.readTimeout = 12_000
            requestHeaders(requestId).forEach(connection::setRequestProperty)
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val message = runCatching {
                    JSONObject(raw).getJSONObject("error").getString("message")
                }.getOrDefault("HTTP $status")
                error(message)
            }
            JSONObject(raw).also { check(it.optBoolean("success", false)) { "API 요청 실패" } }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseSettings(data: JSONObject) = RemoteSettings(
        riskSensitivity = data.optString("risk_sensitivity", "normal"),
        vibrationEnabled = data.optBoolean("vibration_enabled", true),
        ttsEnabled = data.optBoolean("tts_enabled", false),
        largeTextEnabled = data.optBoolean("large_text_enabled", false),
        highContrastEnabled = data.optBoolean("high_contrast_enabled", false)
    )

    internal fun riskLevel(value: String) = when (value.lowercase()) {
        "danger" -> RiskLevel.DANGER
        "warning" -> RiskLevel.WARNING
        else -> RiskLevel.SAFE
    }

    private fun titleFor(level: RiskLevel) = when (level) {
        RiskLevel.SAFE -> "안전 입력 확인 중"
        RiskLevel.WARNING -> "주의가 필요한 입력이에요"
        RiskLevel.DANGER -> "위험한 입력이 감지됐어요"
    }

    internal fun maskSensitive(text: String): String = text
        .replace(Regex("\\b\\d{6}-?\\d{7}\\b"), "******-*******")
        .replace(Regex("\\b01[016789][- ]?\\d{3,4}[- ]?\\d{4}\\b"), "010-****-****")
        .replace(Regex("(?i)(OTP|인증번호|비밀번호)(.{0,12})\\d{4,}"), "$1$2****")

    private fun safeSpeaker(value: String) = when (value.lowercase()) {
        "user", "other" -> value.lowercase()
        else -> "unknown"
    }

    internal fun requestHeaders(requestId: String?): Map<String, String> = buildMap {
        put("X-API-Key", BuildConfig.KEYBACK_API_KEY)
        put("Accept", "application/json")
        requestId?.let { put("X-Request-ID", it) }
    }

    private fun encode(value: String) = java.net.URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun checkConfigured() = check(configured) { "KEYBACK_API_KEY가 설정되지 않았습니다." }

    private fun <T> async(callback: (Result<T>) -> Unit, block: () -> T) {
        executor.execute { callback(runCatching(block)) }
    }
}
