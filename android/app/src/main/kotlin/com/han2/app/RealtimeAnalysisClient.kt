package com.han2.app

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

enum class RealtimeConnectionState { DISCONNECTED, CONNECTING, CONNECTED }
enum class RealtimeResultStage { PROVISIONAL, FINAL }

data class RealtimeResult(
    val sessionId: String,
    val sequence: Long,
    val stage: RealtimeResultStage,
    val analysis: RemoteAnalysis
)

data class RealtimeDraft(
    val sessionId: String,
    val sequence: Long,
    val requestId: String,
    val deviceId: String,
    val appPackage: String,
    val conversationId: String?,
    val messageId: String?,
    val inputText: String,
    val maskedText: String,
    val contextMessages: List<ContextMessage>,
    val localResult: RiskResult,
    val clientVersion: String,
    val clientTimestamp: String = Instant.now().toString()
)

internal object KeyBackNetwork {
    val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(7, TimeUnit.SECONDS)
        .build()
}

class RealtimeAnalysisClient(
    private val webSocketUrl: String = BuildConfig.KEYBACK_REALTIME_URL,
    private val apiKey: String = BuildConfig.KEYBACK_API_KEY,
    private val client: OkHttpClient = KeyBackNetwork.client
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val _connectionState = MutableStateFlow(RealtimeConnectionState.DISCONNECTED)
    private val _results = MutableSharedFlow<RealtimeResult>(extraBufferCapacity = 16)
    val connectionState: StateFlow<RealtimeConnectionState> = _connectionState.asStateFlow()
    val results: SharedFlow<RealtimeResult> = _results.asSharedFlow()

    private var socket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var active = false
    private var generation = 0L
    private var reconnectAttempt = 0
    private var pendingDraft: RealtimeDraft? = null

    fun connect() {
        val currentGeneration = synchronized(lock) {
            if (active && (socket != null || _connectionState.value == RealtimeConnectionState.CONNECTING)) return
            active = true
            ++generation
        }
        openSocket(currentGeneration)
    }

    fun sendDraft(draft: RealtimeDraft) {
        val encoded = encodeDraft(draft).toString()
        val currentSocket = synchronized(lock) {
            pendingDraft = draft
            socket.takeIf { _connectionState.value == RealtimeConnectionState.CONNECTED }
        }
        if (currentSocket?.send(encoded) == true) synchronized(lock) {
            if (pendingDraft == draft) pendingDraft = null
        }
    }

    fun disconnect() {
        val oldSocket = synchronized(lock) {
            active = false
            generation++
            reconnectJob?.cancel()
            reconnectJob = null
            pendingDraft = null
            socket.also { socket = null }
        }
        oldSocket?.close(1000, null)
        _connectionState.value = RealtimeConnectionState.DISCONNECTED
    }

    fun clearPendingDraft() {
        synchronized(lock) { pendingDraft = null }
    }

    fun close() {
        disconnect()
        scope.cancel()
    }

    private fun openSocket(expectedGeneration: Long) {
        if (apiKey.isBlank() || webSocketUrl.isBlank()) return
        _connectionState.value = RealtimeConnectionState.CONNECTING
        val request = Request.Builder().url(webSocketUrl).header("X-API-Key", apiKey).build()
        val newSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val pending = synchronized(lock) {
                    if (!active || generation != expectedGeneration) {
                        webSocket.close(1000, null)
                        return
                    }
                    socket = webSocket
                    reconnectAttempt = 0
                    pendingDraft
                }
                _connectionState.value = RealtimeConnectionState.CONNECTED
                pending?.let { sendDraft(it) }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { parseEvent(text) }.getOrNull()?.let(_results::tryEmit)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = handleDisconnect(expectedGeneration)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = handleDisconnect(expectedGeneration)
        })
        synchronized(lock) {
            if (active && generation == expectedGeneration) socket = newSocket else newSocket.close(1000, null)
        }
    }

    private fun handleDisconnect(expectedGeneration: Long) {
        val retryDelay = synchronized(lock) {
            if (!active || generation != expectedGeneration) return
            socket = null
            val delayMs = (500L shl reconnectAttempt.coerceAtMost(4)).coerceAtMost(8_000L)
            reconnectAttempt++
            delayMs
        }
        _connectionState.value = RealtimeConnectionState.DISCONNECTED
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(retryDelay)
            if (synchronized(lock) { active && generation == expectedGeneration }) openSocket(expectedGeneration)
        }
    }

    internal fun parseEvent(raw: String): RealtimeResult? {
        val envelope = JSONObject(raw)
        val stage = when (envelope.optString("type")) {
            "provisional_result" -> RealtimeResultStage.PROVISIONAL
            "final_result" -> RealtimeResultStage.FINAL
            else -> return null
        }
        val sessionId = envelope.optString("session_id")
        if (sessionId.isBlank() || !envelope.has("sequence")) return null
        val data = envelope.optJSONObject("data") ?: return null
        return RealtimeResult(sessionId, envelope.getLong("sequence"), stage, KeyBackApi.parseAnalysisData(data, ""))
    }

    internal fun encodeDraft(draft: RealtimeDraft) = JSONObject().apply {
        put("type", "draft_changed")
        put("session_id", draft.sessionId)
        put("sequence", draft.sequence)
        put("payload", JSONObject().apply {
            put("request_id", draft.requestId)
            put("device_id", draft.deviceId)
            put("app_package", draft.appPackage)
            put("conversation_id", draft.conversationId ?: JSONObject.NULL)
            put("message_id", draft.messageId ?: JSONObject.NULL)
            put("input_text", draft.inputText.take(1000))
            put("masked_text", draft.maskedText.take(1000))
            put("context_messages", JSONArray().apply {
                draft.contextMessages.takeLast(5).forEach { message ->
                    put(JSONObject().put("speaker", message.speaker).put("text", KeyBackApi.maskSensitive(message.text.take(300))))
                }
            })
            put("local_detection", JSONObject().apply {
                put("detected", draft.localResult.level != RiskLevel.SAFE)
                put("risk_types", JSONArray())
                put("local_score", when (draft.localResult.level) {
                    RiskLevel.SAFE -> 0
                    RiskLevel.WARNING -> 55
                    RiskLevel.DANGER -> 90
                })
            })
            put("client_timestamp", draft.clientTimestamp)
            put("client_version", draft.clientVersion)
        })
    }
}

internal class RealtimeSessionState(private val idFactory: () -> String = { UUID.randomUUID().toString() }) {
    var sessionId: String? = null
        private set
    var latestSequence = 0L
        private set
    var active = false
        private set
    var lastSentText = ""
        private set
    private var currentDraftText = ""

    fun start(): String {
        sessionId = idFactory()
        latestSequence = 0
        lastSentText = ""
        currentDraftText = ""
        active = true
        return sessionId!!
    }

    fun stop() {
        active = false
        sessionId = null
        latestSequence = 0
        lastSentText = ""
        currentDraftText = ""
    }

    fun draftChanged(text: String): Boolean {
        if (!active || text == currentDraftText) return false
        currentDraftText = text
        return true
    }

    fun nextSequence(text: String): Long? {
        if (!active || text == lastSentText) return null
        currentDraftText = text
        lastSentText = text
        return ++latestSequence
    }

    fun accepts(result: RealtimeResult): Boolean = active &&
        result.sessionId == sessionId && result.sequence == latestSequence &&
        currentDraftText == lastSentText
}

internal class DraftDebouncer(
    private val scope: CoroutineScope,
    private val delayMs: Long,
    private val emit: (String, RiskResult) -> Unit
) {
    private var job: Job? = null

    fun submit(text: String, local: RiskResult, immediate: Boolean) {
        job?.cancel()
        job = scope.launch {
            if (!immediate) delay(delayMs)
            emit(text, local)
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }
}
