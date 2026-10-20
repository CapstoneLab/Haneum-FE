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
            rule.regex.findAll(value).mapNotNull { match: MatchResult ->
                val valid = when (rule.type) {
                    PrivacyType.RESIDENT_ID -> isValidResidentId(match.value)
                    PrivacyType.CARD -> isValidCardNumber(match.value)
                    else -> true
                }
                if (valid) PrivacyFinding(rule.type, match.range, rule.label) else null
            }.toList()
        })
    }

    private fun isValidResidentId(value: String): Boolean {
        val digits = value.filter(Char::isDigit)
        if (digits.length != 13) return false
        val weights = intArrayOf(2, 3, 4, 5, 6, 7, 8, 9, 2, 3, 4, 5)
        val checksum = (0 until 12).sumOf { digits[it].digitToInt() * weights[it] }
        return (11 - checksum % 11) % 10 == digits[12].digitToInt()
    }

    private fun isValidCardNumber(value: String): Boolean {
        val digits = value.filter(Char::isDigit)
        if (digits.length !in 16..19) return false
        var sum = 0
        var double = false
        for (index in digits.lastIndex downTo 0) {
            var digit = digits[index].digitToInt()
            if (double) {
                digit *= 2
                if (digit > 9) digit -= 9
            }
            sum += digit
            double = !double
        }
        return sum % 10 == 0
    }
}
