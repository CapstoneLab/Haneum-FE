package com.han2.app

import org.junit.Assert.assertEquals
import org.junit.Test

class HangulComposerTest {
    @Test fun composesSyllablesAndWords() {
        val composer = HangulComposer()
        "ㅎㅏㄴㄱㅡㄹ".forEach(composer::input)
        assertEquals("한글", composer.text())
    }

    @Test fun movesFinalConsonantToNextSyllable() {
        val composer = HangulComposer()
        "ㄱㅏㄴㅏ".forEach(composer::input)
        assertEquals("가나", composer.text())
    }

    @Test fun composesAndSplitsCompoundFinal() {
        val composer = HangulComposer()
        "ㄷㅏㄹㄱㅏ".forEach(composer::input)
        assertEquals("달가", composer.text())
    }

    @Test fun backspaceDecomposesSyllable() {
        val composer = HangulComposer()
        "ㅎㅏㄴ".forEach(composer::input)
        assertEquals("한", composer.text())
        composer.backspace()
        assertEquals("하", composer.text())
        composer.backspace()
        assertEquals("ㅎ", composer.text())
    }
}
