package com.han2.app

enum class RiskLevel { SAFE, WARNING, DANGER }

data class RiskResult(val level: RiskLevel, val title: String, val reason: String)

object RiskAnalyzer {
    private val residentId = Regex("(?<!\\d)\\d{6}-?\\d{7}(?!\\d)")
    private val phone = Regex("(?<!\\d)01[016789][- ]?\\d{3,4}[- ]?\\d{4}(?!\\d)")
    private val authentication = Regex("(?i)(주민|비밀번호|OTP|인증번호).{0,12}\\d{4,}")
    private val sensitiveIntent = Regex("(?i)(주민(?:등록)?번호|계좌번호|카드번호|비밀번호|인증번호|OTP|보안코드|전화번호|휴대폰번호)")
    private val partialPhone = Regex("(?<!\\d)01[016789]?\\d{0,7}(?!\\d)")
    private val longSensitiveNumber = Regex("(?<!\\d)\\d{13,19}(?!\\d)")
    private val fraudContext = Regex("(송금|입금|계좌|상품권|대출|앱 설치|기관 사칭)")
    private val suspiciousLink = Regex("(?i)(https?://|www\\.|bit\\.ly|tinyurl|t\\.me|open\\.kakao)")
    private val possibleSensitiveNumber = Regex("(?<!\\d)\\d{7,12}(?!\\d)")

    fun analyze(text: String): RiskResult = when {
        residentId.containsMatchIn(text) || authentication.containsMatchIn(text) || longSensitiveNumber.containsMatchIn(text) ->
            RiskResult(RiskLevel.DANGER, "민감정보가 포함됐어요", "생년월일·인증정보로 보이는 번호가 감지됐습니다.")
        fraudContext.containsMatchIn(text) || suspiciousLink.containsMatchIn(text) ->
            RiskResult(RiskLevel.WARNING, "금융사기 가능성을 확인하세요", "송금 유도 표현 또는 안전하지 않을 수 있는 링크가 감지됐습니다.")
        phone.containsMatchIn(text) ->
            RiskResult(RiskLevel.WARNING, "개인정보로 보이는 번호예요", "휴대전화 번호 형식이 감지됐습니다.")
        possibleSensitiveNumber.containsMatchIn(text) ->
            RiskResult(RiskLevel.WARNING, "민감정보 가능성 확인 중", "번호가 민감정보로 이어질 수 있어 확인하고 있습니다.")
        sensitiveIntent.containsMatchIn(text) || partialPhone.containsMatchIn(text) ->
            RiskResult(RiskLevel.WARNING, "민감정보 입력 가능성", "민감정보 입력으로 이어질 수 있어 미리 확인하고 있습니다.")
        else -> RiskResult(RiskLevel.SAFE, "안전 입력 확인 중", "")
    }
}
