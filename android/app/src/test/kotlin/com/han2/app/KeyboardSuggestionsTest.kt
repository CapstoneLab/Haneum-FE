package com.han2.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardSuggestionsTest {
    @Test
    fun blankTextProvidesCommonSuggestions() {
        assertEquals(listOf("안녕하세요", "감사합니다", "네, 알겠습니다"), KeyboardSuggestions.forText(""))
    }

    @Test
    fun contextProvidesMatchingCompletions() {
        assertEquals(listOf("합니다", "드립니다"), KeyboardSuggestions.forText("감사"))
    }

    @Test
    fun unknownContextFallsBackToGeneralSuggestions() {
        assertTrue(KeyboardSuggestions.forText("오늘 일정").contains("확인했습니다"))
    }
}
