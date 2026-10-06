package com.han2.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyDetectorTest {
    private val detector: PrivacyDetector = RegexPrivacyDetector()

    @Test fun detectsEmail() = assertType("test@example.com", PrivacyType.EMAIL)
    @Test fun detectsPhone() = assertType("010-1234-5678", PrivacyType.PHONE)
    @Test fun detectsResidentIdShape() = assertType("900101-1234567", PrivacyType.RESIDENT_ID)
    @Test fun detectsCardShape() = assertType("1234 5678 9012 3456", PrivacyType.CARD)
    @Test fun detectsAccountWithContext() = assertType("계좌 123-456-789012", PrivacyType.ACCOUNT)

    @Test
    fun ordinarySentenceIsSafe() {
        assertFalse(detector.detect("내일 세 시에 만나요").suspected)
    }

    private fun assertType(text: String, type: PrivacyType) {
        val result = detector.detect(text)
        assertTrue(result.suspected)
        assertEquals(type, result.findings.first().type)
    }
}
