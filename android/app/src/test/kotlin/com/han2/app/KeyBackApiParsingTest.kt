package com.han2.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyBackApiParsingTest {
    @Test
    fun legacyResponseWithoutConversationRiskStillParses() {
        val response = JSONObject(
            """{"success":true,"data":{"request_id":"r1","risk_score":46,"risk_level":"warning","recommended_action":"show_warning"}}"""
        )
        val parsed = KeyBackApi.parseAnalysisResponse(response, "fallback")
        assertEquals(RiskLevel.WARNING, parsed.result.level)
        assertNull(parsed.conversationRisk)
    }

    @Test
    fun unknownConversationStatusFallsBackToNormal() {
        val response = JSONObject(
            """{"success":true,"data":{"risk_score":1,"risk_level":"unknown","recommended_action":"none","conversation_risk":{"conversation_id":"c1","status":"future_value"}}}"""
        )
        val parsed = KeyBackApi.parseAnalysisResponse(response, "fallback")
        assertEquals(ConversationStatus.NORMAL, parsed.conversationRisk?.status)
    }

    @Test
    fun requestIdHeaderMatchesBodyAndApiKeyIsNotInBody() {
        val requestId = "request-123"
        val body = KeyBackApi.buildAnalyzeRequest(
            requestId, "device", "version",
            AnalyzeInput("messaging.app", "conversation", "message", "안녕하세요", emptyList(), RiskResult(RiskLevel.SAFE, "", ""))
        )
        assertEquals(requestId, body.getString("request_id"))
        assertEquals(requestId, KeyBackApi.requestHeaders(requestId)["X-Request-ID"])
        assertFalse(body.has("api_key"))
        assertFalse(body.has("X-API-Key"))
    }

    @Test
    fun contextMessagesAreMaskedAndBounded() {
        val body = KeyBackApi.buildAnalyzeRequest(
            "r", "d", "v",
            AnalyzeInput(
                "messaging.app", "c", "m", "의미 있는 문장",
                (1..7).map { ContextMessage("other", "010-1234-5678 ${"x".repeat(290)}") },
                RiskResult(RiskLevel.SAFE, "", "")
            )
        )
        val context = body.getJSONArray("context_messages")
        assertTrue(context.length() <= 5)
        val combined = (0 until context.length()).joinToString { context.getJSONObject(it).getString("text") }
        assertFalse(combined.contains("010-1234-5678"))
        assertTrue(combined.length <= 1000)
    }
}
