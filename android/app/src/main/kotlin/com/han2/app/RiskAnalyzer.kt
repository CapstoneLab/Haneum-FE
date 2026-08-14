package com.han2.app

enum class RiskLevel { SAFE, WARNING, DANGER }

data class RiskResult(val level: RiskLevel, val title: String, val reason: String)

object RiskAnalyzer {
    private val residentId = Regex("\\b\\d{6}-?\\d{7}\\b")
    private val phone = Regex("\\b01[016789][- ]?\\d{3,4}[- ]?\\d{4}\\b")
    private val authentication = Regex("(?i)(주민|비밀번호|OTP|인증번호).{0,12}\\d{4,}")
    private val fraudContext = Regex("(송금|입금|계좌|상품권|대출|앱 설치|기관 사칭)")
    private val suspiciousLink = Regex("(?i)(http://|bit\\.ly|tinyurl)")

    fun analyze(text: String): RiskResult = when {
        residentId.containsMatchIn(text) || authentication.containsMatchIn(text) ->
            RiskResult(RiskLevel.DANGER, "민감정보가 포함됐어요", "생년월일·인증정보로 보이는 번호가 감지됐습니다.")
        fraudContext.containsMatchIn(text) || suspiciousLink.containsMatchIn(text) ->
            RiskResult(RiskLevel.WARNING, "금융사기 가능성을 확인하세요", "송금 유도 표현 또는 안전하지 않을 수 있는 링크가 감지됐습니다.")
        phone.containsMatchIn(text) ->
            RiskResult(RiskLevel.WARNING, "개인정보로 보이는 번호예요", "휴대전화 번호 형식이 감지됐습니다.")
        else -> RiskResult(RiskLevel.SAFE, "안전 입력 확인 중", "")
    }
}
