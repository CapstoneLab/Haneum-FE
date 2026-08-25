package com.han2.app

import java.security.MessageDigest
import java.util.UUID

enum class ConversationStatus(val rank: Int) {
    NORMAL(0), WATCHING(1), CONFIRMED(2);

    companion object {
        fun fromWire(value: String?): ConversationStatus = when (value?.lowercase()) {
            "watching" -> WATCHING
            "confirmed" -> CONFIRMED
            else -> NORMAL
        }
    }
}

data class ConversationRisk(
    val conversationId: String,
    val status: ConversationStatus,
    val previousStatus: ConversationStatus = ConversationStatus.NORMAL,
    val changed: Boolean = false,
    val latched: Boolean = false,
    val suspicionCount: Int = 0,
    val peakScore: Int = 0
)

data class ContextMessage(val speaker: String, val text: String)

data class AnalyzeInput(
    val appPackage: String,
    val conversationId: String?,
    val messageId: String?,
    val text: String,
    val contextMessages: List<ContextMessage>,
    val local: RiskResult
)

object ConversationIdentity {
    fun create(appPackage: String, editorIdentity: String?, sessionId: String): String {
        val stablePart = editorIdentity?.takeIf { it.isNotBlank() } ?: "ime-session:$sessionId"
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest("$appPackage|$stablePart".toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

class DraftSession(private val newId: () -> String = { UUID.randomUUID().toString() }) {
    var conversationId: String? = null
        private set
    var messageId: String = newId()
        private set
    var revision: Long = 0
        private set
    private var hadText = false
    private var previousText = ""
    private var latchedDanger: RiskResult? = null

    fun switchConversation(id: String) {
        if (conversationId != id) {
            conversationId = id
            resetDraft()
        }
    }

    fun textChanged(text: String) {
        revision++
        if (text.isBlank()) {
            if (hadText) resetDraft()
        } else {
            // Host apps may clear a sent draft without notifying the IME. Detect the next
            // newly-started short draft so it cannot reuse the previous message id.
            if (hadText && previousText.length >= 4 && text.length <= 2 && !previousText.startsWith(text)) {
                resetDraft()
            }
            hadText = true
            previousText = text
        }
    }

    fun sent() = resetDraft()

    fun latchDanger(result: RiskResult): RiskResult {
        if (result.level == RiskLevel.DANGER && latchedDanger == null) latchedDanger = result
        return latchedDanger ?: result
    }

    private fun resetDraft() {
        messageId = newId()
        revision++
        hadText = false
        previousText = ""
        latchedDanger = null
    }
}

class ConversationStateMachine {
    private val states = mutableMapOf<String, ConversationStatus>()
    private val latestSequences = mutableMapOf<String, Long>()

    fun current(conversationId: String): ConversationStatus =
        states[conversationId] ?: ConversationStatus.NORMAL

    fun restore(conversationId: String, status: ConversationStatus) {
        states[conversationId] = maxOf(current(conversationId), status, compareBy { it.rank })
    }

    fun apply(conversationId: String, incoming: ConversationStatus, sequence: Long): ConversationStatus {
        val latest = latestSequences[conversationId] ?: Long.MIN_VALUE
        if (sequence < latest) return current(conversationId)
        latestSequences[conversationId] = sequence
        val merged = maxOf(current(conversationId), incoming, compareBy { it.rank })
        states[conversationId] = merged
        return merged
    }
}

internal fun isMeaningfulForRemoteAnalysis(text: String): Boolean =
    text.count { !it.isWhitespace() } >= 4

internal fun shouldRequestAnalysis(
    enabled: Boolean,
    appEnabled: Boolean,
    sensitiveField: Boolean,
    text: String,
    lastRequestedText: String
): Boolean = enabled && appEnabled && !sensitiveField &&
    isMeaningfulForRemoteAnalysis(text) && text != lastRequestedText

data class ConversationPresentation(val message: String?, val foreground: Int, val background: Int)

object ConversationUi {
    const val ORANGE_TEXT = 0xFF895B12.toInt()
    const val ORANGE_BACKGROUND = 0xFFFFF4CC.toInt()
    const val RED_TEXT = 0xFF9D2831.toInt()
    const val RED_BACKGROUND = 0xFFFFE2E4.toInt()

    fun presentation(status: ConversationStatus): ConversationPresentation = when (status) {
        ConversationStatus.NORMAL -> ConversationPresentation(null, 0, 0)
        ConversationStatus.WATCHING -> ConversationPresentation(
            "의심스러운 대화 흐름을 확인하고 있습니다. 개인정보 입력에 주의하세요.",
            ORANGE_TEXT, ORANGE_BACKGROUND
        )
        ConversationStatus.CONFIRMED -> ConversationPresentation(
            "개인정보 탈취 또는 사기 위험이 감지되었습니다. 전송하기 전에 내용을 다시 확인하세요.",
            RED_TEXT, RED_BACKGROUND
        )
    }
}
