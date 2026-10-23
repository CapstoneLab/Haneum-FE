package com.han2.app

object KeyboardSuggestions {
    private val defaultSuggestions = listOf("안녕하세요", "감사합니다", "네, 알겠습니다")

    fun forText(text: String): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return defaultSuggestions

        return when {
            trimmed.endsWith("안녕") -> listOf("하세요", "하십니까")
            trimmed.endsWith("감사") -> listOf("합니다", "드립니다")
            trimmed.endsWith("확인") -> listOf("했습니다", "부탁드립니다")
            trimmed.endsWith("부탁") -> listOf("드립니다", "드리겠습니다")
            trimmed.endsWith("죄송") -> listOf("합니다", "합니다. 확인해 보겠습니다")
            else -> listOf("네", "감사합니다", "확인했습니다")
        }
    }
}
