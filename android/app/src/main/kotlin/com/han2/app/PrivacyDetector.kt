package com.han2.app

enum class PrivacyType { EMAIL, PHONE, RESIDENT_ID, CARD, ACCOUNT, AUTHENTICATION }

data class PrivacyFinding(
    val type: PrivacyType,
    val range: IntRange,
    val label: String
)

data class PrivacyScanResult(val findings: List<PrivacyFinding>) {
    val suspected: Boolean get() = findings.isNotEmpty()
}

/** Replace this implementation with an on-device ML detector without changing the IME. */
fun interface PrivacyDetector {
    fun detect(text: CharSequence): PrivacyScanResult
}

class RegexPrivacyDetector : PrivacyDetector {
    private data class Rule(val type: PrivacyType, val label: String, val regex: Regex)

    private val rules = listOf(
        Rule(PrivacyType.EMAIL, "이메일 주소", Regex("(?i)(?<![\\w.+-])[\\w.+-]+@[a-z0-9-]+(?:\\.[a-z0-9-]+)+(?![\\w.-])")),
        Rule(PrivacyType.PHONE, "전화번호", Regex("(?<!\\d)(?:01[016789]|0[2-6][1-5]?)[- .]?\\d{3,4}[- .]?\\d{4}(?!\\d)")),
        Rule(PrivacyType.RESIDENT_ID, "주민등록번호 형태", Regex("(?<!\\d)\\d{6}[- ]?[1-8]\\d{6}(?!\\d)")),
        Rule(PrivacyType.CARD, "카드번호 형태", Regex("(?<!\\d)(?:\\d[ -]?){15,18}\\d(?!\\d)")),
        Rule(PrivacyType.ACCOUNT, "계좌번호 형태", Regex("(?i)(?:계좌|account).{0,12}(?<!\\d)\\d{2,6}(?:[- ]?\\d{2,6}){2,4}(?!\\d)")),
        Rule(PrivacyType.AUTHENTICATION, "인증정보", Regex("(?i)(?:비밀번호|인증번호|otp|보안코드).{0,12}\\d{4,8}"))
    )

    override fun detect(text: CharSequence): PrivacyScanResult {
        val value = text.toString()
        return PrivacyScanResult(rules.flatMap { rule ->
            rule.regex.findAll(value).map { PrivacyFinding(rule.type, it.range, rule.label) }.toList()
        })
    }
}
