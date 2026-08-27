package com.han2.app

import org.junit.Assert.assertEquals
import org.junit.Test

class RiskAnalyzerTest {
    @Test
    fun partialNumberShowsWarningBeforeConfirmedSensitiveInformation() {
        assertEquals(RiskLevel.WARNING, RiskAnalyzer.analyze("12345678").level)
    }

    @Test
    fun residentNumberRemainsDanger() {
        assertEquals(RiskLevel.DANGER, RiskAnalyzer.analyze("900101-1234567").level)
    }

    @Test
    fun ordinaryTextRemainsSafe() {
        assertEquals(RiskLevel.SAFE, RiskAnalyzer.analyze("안녕하세요").level)
    }

    @Test
    fun ordinaryShortNumberDoesNotTriggerSensitiveNumberWarning() {
        assertEquals(RiskLevel.SAFE, RiskAnalyzer.analyze("약속 시간은 1234입니다").level)
    }

    @Test
    fun httpsAndCommonShortLinksShowWarning() {
        assertEquals(RiskLevel.WARNING, RiskAnalyzer.analyze("https://example.com 확인").level)
        assertEquals(RiskLevel.WARNING, RiskAnalyzer.analyze("open.kakao.com 링크").level)
    }

    @Test
    fun sensitiveIntentWarnsBeforeTheNumberIsEntered() {
        assertEquals(RiskLevel.WARNING, RiskAnalyzer.analyze("주민번호 입력할게요").level)
        assertEquals(RiskLevel.WARNING, RiskAnalyzer.analyze("계좌번호는").level)
    }

    @Test
    fun partialPhoneNumberWarnsEarly() {
        assertEquals(RiskLevel.WARNING, RiskAnalyzer.analyze("010").level)
    }

    @Test
    fun residentNumberStillMatchesWhenFollowedByKoreanText() {
        assertEquals(RiskLevel.DANGER, RiskAnalyzer.analyze("900101-1234567입니다").level)
    }
}
