package com.han2.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationRiskTest {
    @Test
    fun safeLocalResultStillAllowsMeaningfulDebouncedRequest() {
        val local = RiskAnalyzer.analyze("오늘 어디에서 만나요")
        assertEquals(RiskLevel.SAFE, local.level)
        assertTrue(shouldRequestAnalysis(true, true, false, "오늘 어디에서 만나요", ""))
    }

    @Test
    fun repeatedDraftKeepsMessageId() {
        val ids = listOf("first", "second", "third").iterator()
        val draft = DraftSession { ids.next() }
        draft.switchConversation("conversation-a")
        val id = draft.messageId
        draft.textChanged("검찰 수사관입니다")
        draft.textChanged("검찰 수사관입니다 확인해주세요")
        assertEquals(id, draft.messageId)
    }

    @Test
    fun sentDraftGetsNewMessageId() {
        val ids = listOf("first", "second", "third").iterator()
        val draft = DraftSession { ids.next() }
        draft.switchConversation("conversation-a")
        val before = draft.messageId
        draft.sent()
        assertNotEquals(before, draft.messageId)
    }

    @Test
    fun watchingUsesOrangePresentation() {
        val ui = ConversationUi.presentation(ConversationStatus.WATCHING)
        assertEquals(ConversationUi.ORANGE_TEXT, ui.foreground)
        assertTrue(ui.message!!.contains("의심스러운 대화 흐름"))
    }

    @Test
    fun confirmedCannotBeDowngradedByNormal() {
        val states = ConversationStateMachine()
        states.apply("a", ConversationStatus.CONFIRMED, 1)
        assertEquals(ConversationStatus.CONFIRMED, states.apply("a", ConversationStatus.NORMAL, 2))
    }

    @Test
    fun conversationsDoNotShareState() {
        val states = ConversationStateMachine()
        states.apply("a", ConversationStatus.CONFIRMED, 1)
        assertEquals(ConversationStatus.NORMAL, states.current("b"))
    }

    @Test
    fun staleResponseCannotOverwriteLatestState() {
        val states = ConversationStateMachine()
        states.apply("a", ConversationStatus.CONFIRMED, 2)
        assertEquals(ConversationStatus.CONFIRMED, states.apply("a", ConversationStatus.NORMAL, 1))
    }

    @Test
    fun passwordFieldNeverRequestsAnalysis() {
        assertFalse(shouldRequestAnalysis(true, true, true, "12345678", ""))
    }

    @Test
    fun distinctTextPolicySkipsIdenticalText() {
        assertFalse(shouldRequestAnalysis(true, true, false, "같은 문장", "같은 문장"))
    }

    @Test
    fun conversationIdentityIsStableAndOpaque() {
        val first = ConversationIdentity.create("messaging.app", "raw-room-name", "session")
        val second = ConversationIdentity.create("messaging.app", "raw-room-name", "different-session")
        assertEquals(first, second)
        assertFalse(first.contains("raw-room-name"))
        assertEquals(64, first.length)
    }

    @Test
    fun dangerRemainsLatchedWhileEditingSameDraft() {
        val draft = DraftSession { "draft" }
        val danger = RiskResult(RiskLevel.DANGER, "danger", "reason")
        assertEquals(RiskLevel.DANGER, draft.latchDanger(danger).level)
        assertEquals(RiskLevel.DANGER, draft.latchDanger(RiskResult(RiskLevel.SAFE, "safe", "")).level)
    }

    @Test
    fun dangerLatchClearsAfterSend() {
        val draft = DraftSession { "draft" }
        draft.latchDanger(RiskResult(RiskLevel.DANGER, "danger", "reason"))
        draft.sent()
        assertEquals(RiskLevel.SAFE, draft.latchDanger(RiskResult(RiskLevel.SAFE, "safe", "")).level)
    }
}
