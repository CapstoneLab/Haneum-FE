package com.han2.app

class HangulComposer {
    private val committed = StringBuilder()
    private var initial: Char? = null
    private var medial: Char? = null
    private var final: Char? = null

    fun input(value: Char): String {
        when {
            value in MEDIALS -> inputVowel(value)
            value in CONSONANTS -> inputConsonant(value)
            else -> {
                commitActive()
                committed.append(value)
            }
        }
        return text()
    }

    fun backspace(): Boolean {
        when {
            final != null -> final = FINAL_SPLIT[final]?.first ?: null
            medial != null -> medial = MEDIAL_SPLIT[medial]?.first ?: null
            initial != null -> initial = INITIAL_SPLIT[initial] ?: null
            committed.isNotEmpty() -> {
                val last = committed.last()
                committed.deleteCharAt(committed.lastIndex)
                if (last.code in HANGUL_BASE..HANGUL_END) {
                    decompose(last)
                    return backspace()
                }
            }
            else -> return false
        }
        return true
    }

    fun text(): String = committed.toString() + activeText()

    fun reset() {
        committed.clear()
        initial = null
        medial = null
        final = null
    }

    private fun inputConsonant(value: Char) {
        when {
            initial == null && medial == null -> initial = value
            initial != null && medial == null -> {
                val doubled = INITIAL_COMBINE[initial to value]
                if (doubled != null) initial = doubled
                else {
                    commitActive()
                    initial = value
                }
            }
            medial != null && final == null -> {
                if (value in FINALS) final = value
                else {
                    commitActive()
                    initial = value
                }
            }
            final != null -> {
                val combined = FINAL_COMBINE[final to value]
                if (combined != null) final = combined
                else {
                    commitActive()
                    initial = value
                }
            }
        }
    }

    private fun inputVowel(value: Char) {
        when {
            medial == null -> medial = value
            final == null -> {
                val combined = MEDIAL_COMBINE[medial to value]
                if (combined != null) medial = combined
                else {
                    commitActive()
                    medial = value
                }
            }
            else -> {
                val oldFinal = final!!
                val split = FINAL_SPLIT[oldFinal]
                if (split != null) {
                    final = split.first
                    commitActive()
                    initial = split.second
                } else {
                    final = null
                    commitActive()
                    initial = oldFinal
                }
                medial = value
            }
        }
    }

    private fun activeText(): String {
        val lead = initial
        val vowel = medial
        if (lead == null || vowel == null) return buildString {
            lead?.let(::append)
            vowel?.let(::append)
            final?.let(::append)
        }
        val leadIndex = INITIALS.indexOf(lead)
        val vowelIndex = MEDIALS.indexOf(vowel)
        val finalIndex = final?.let { FINALS.indexOf(it) + 1 } ?: 0
        if (leadIndex < 0 || vowelIndex < 0 || finalIndex < 0) return "$lead$vowel${final ?: ""}"
        return ((leadIndex * 21 + vowelIndex) * 28 + finalIndex + HANGUL_BASE).toChar().toString()
    }

    private fun commitActive() {
        committed.append(activeText())
        initial = null
        medial = null
        final = null
    }

    private fun decompose(syllable: Char) {
        val offset = syllable.code - HANGUL_BASE
        initial = INITIALS[offset / (21 * 28)]
        medial = MEDIALS[(offset % (21 * 28)) / 28]
        val finalIndex = offset % 28
        final = if (finalIndex == 0) null else FINALS[finalIndex - 1]
    }

    companion object {
        private const val HANGUL_BASE = 0xAC00
        private const val HANGUL_END = 0xD7A3
        private const val INITIALS = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
        private const val MEDIALS = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ"
        private const val FINALS = "ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ"
        private const val CONSONANTS = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"

        private val INITIAL_COMBINE = mapOf(
            ('ㄱ' to 'ㄱ') to 'ㄲ', ('ㄷ' to 'ㄷ') to 'ㄸ', ('ㅂ' to 'ㅂ') to 'ㅃ',
            ('ㅅ' to 'ㅅ') to 'ㅆ', ('ㅈ' to 'ㅈ') to 'ㅉ'
        )
        private val INITIAL_SPLIT = mapOf('ㄲ' to 'ㄱ', 'ㄸ' to 'ㄷ', 'ㅃ' to 'ㅂ', 'ㅆ' to 'ㅅ', 'ㅉ' to 'ㅈ')
        private val MEDIAL_COMBINE = mapOf(
            ('ㅗ' to 'ㅏ') to 'ㅘ', ('ㅗ' to 'ㅐ') to 'ㅙ', ('ㅗ' to 'ㅣ') to 'ㅚ',
            ('ㅜ' to 'ㅓ') to 'ㅝ', ('ㅜ' to 'ㅔ') to 'ㅞ', ('ㅜ' to 'ㅣ') to 'ㅟ',
            ('ㅡ' to 'ㅣ') to 'ㅢ', ('ㅑ' to 'ㅣ') to 'ㅒ', ('ㅕ' to 'ㅣ') to 'ㅖ'
        )
        private val MEDIAL_SPLIT = MEDIAL_COMBINE.entries.associate { it.value to it.key }
        private val FINAL_COMBINE = mapOf(
            ('ㄱ' to 'ㅅ') to 'ㄳ', ('ㄴ' to 'ㅈ') to 'ㄵ', ('ㄴ' to 'ㅎ') to 'ㄶ',
            ('ㄹ' to 'ㄱ') to 'ㄺ', ('ㄹ' to 'ㅁ') to 'ㄻ', ('ㄹ' to 'ㅂ') to 'ㄼ',
            ('ㄹ' to 'ㅅ') to 'ㄽ', ('ㄹ' to 'ㅌ') to 'ㄾ', ('ㄹ' to 'ㅍ') to 'ㄿ',
            ('ㄹ' to 'ㅎ') to 'ㅀ', ('ㅂ' to 'ㅅ') to 'ㅄ', ('ㄱ' to 'ㄱ') to 'ㄲ',
            ('ㅅ' to 'ㅅ') to 'ㅆ'
        )
        private val FINAL_SPLIT = FINAL_COMBINE.entries.associate { it.value to it.key }
    }
}
