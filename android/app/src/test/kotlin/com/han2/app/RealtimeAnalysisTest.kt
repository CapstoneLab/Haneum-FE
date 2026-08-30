package com.han2.app

import android.text.InputType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeAnalysisTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun debounceEmitsOnlyLatestDraftOnce() = runTest {
        val emitted = mutableListOf<String>()
        val safe = RiskResult(RiskLevel.SAFE, "", "")
        val debouncer = DraftDebouncer(this, 300) { text, _ -> emitted += text }

        debouncer.submit("first", safe, immediate = false)
        advanceTimeBy(200)
        debouncer.submit("latest", safe, immediate = false)
        advanceTimeBy(299)
        assertTrue(emitted.isEmpty())
        advanceTimeBy(1)
        runCurrent()

        assertEquals(listOf("latest"), emitted)
    }

    @Test
    fun sessionsHaveIndependentIdsAndIncreasingSequences() {
        var id = 0
        val state = RealtimeSessionState { "session-${++id}" }
        val first = state.start()
        assertEquals(1L, state.nextSequence("draft one"))
        assertNull(state.nextSequence("draft one"))
        assertEquals(2L, state.nextSequence("draft two"))
        state.stop()

        val second = state.start()
        assertNotEquals(first, second)
        assertEquals(1L, state.nextSequence("new session"))
    }

    @Test
    fun staleAndClosedSessionResultsAreRejected() {
        val state = RealtimeSessionState { "current" }
        state.start()
        state.nextSequence("one")
        state.nextSequence("two")
        val analysis = RemoteAnalysis("r", RiskResult(RiskLevel.SAFE, "", ""), 0, "none")

        assertFalse(state.accepts(RealtimeResult("current", 1, RealtimeResultStage.FINAL, analysis)))
        assertFalse(state.accepts(RealtimeResult("other", 2, RealtimeResultStage.FINAL, analysis)))
        assertTrue(state.accepts(RealtimeResult("current", 2, RealtimeResultStage.PROVISIONAL, analysis)))
        assertTrue(state.accepts(RealtimeResult("current", 2, RealtimeResultStage.FINAL, analysis)))
        state.draftChanged("")
        assertFalse(state.accepts(RealtimeResult("current", 2, RealtimeResultStage.FINAL, analysis)))
        state.stop()
        assertFalse(state.accepts(RealtimeResult("current", 2, RealtimeResultStage.FINAL, analysis)))
    }

    @Test
    fun allPasswordVariationsAreSensitive() {
        assertTrue(isSensitiveInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertTrue(isSensitiveInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD))
        assertTrue(isSensitiveInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
        assertTrue(isSensitiveInputType(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD))
        assertFalse(isSensitiveInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL))
    }

    @Test
    fun draftEnvelopeMatchesBackendContractAndMasksSensitiveText() {
        val client = RealtimeAnalysisClient("ws://localhost/realtime", "key")
        val json = client.encodeDraft(RealtimeDraft(
            sessionId = "session",
            sequence = 3,
            requestId = "request",
            deviceId = "device",
            appPackage = "chat.app",
            conversationId = null,
            messageId = "message",
            inputText = "OTP 123456",
            maskedText = KeyBackApi.maskSensitive("OTP 123456"),
            contextMessages = emptyList(),
            localResult = RiskResult(RiskLevel.DANGER, "", ""),
            clientVersion = "1.0",
            clientTimestamp = "2026-08-29T00:00:00Z"
        ))

        assertEquals("draft_changed", json.getString("type"))
        assertEquals(3, json.getLong("sequence"))
        val payload = json.getJSONObject("payload")
        assertTrue(payload.isNull("conversation_id"))
        assertEquals(0, payload.getJSONArray("context_messages").length())
        assertEquals("request", payload.getString("request_id"))
        assertFalse(payload.getString("masked_text").contains("123456"))
        assertTrue(payload.getJSONObject("local_detection").getBoolean("detected"))
        client.close()
    }

    @Test
    fun provisionalAndFinalEventsParseWithoutCrashingOnUnknownTypes() {
        val client = RealtimeAnalysisClient("ws://localhost/realtime", "key")
        val data = JSONObject().put("request_id", "r").put("risk_level", "warning")
            .put("risk_score", 55).put("recommended_action", "show_warning")
        val provisional = client.parseEvent(JSONObject().put("type", "provisional_result")
            .put("session_id", "s").put("sequence", 1).put("data", data).toString())
        val final = client.parseEvent(JSONObject().put("type", "final_result")
            .put("session_id", "s").put("sequence", 1).put("data", data).toString())

        assertEquals(RealtimeResultStage.PROVISIONAL, provisional?.stage)
        assertEquals(RealtimeResultStage.FINAL, final?.stage)
        assertNull(client.parseEvent("{\"type\":\"future_event\"}"))
        assertNull(runCatching { client.parseEvent("not-json") }.getOrNull())
        client.close()
    }
}
