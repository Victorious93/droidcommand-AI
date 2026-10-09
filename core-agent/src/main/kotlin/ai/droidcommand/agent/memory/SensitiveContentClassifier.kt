package ai.droidcommand.agent.memory

/**
 * Heuristic, pattern-based sensitivity check (Part 1 §2 / Part 4 §19: credentials must never reach
 * ordinary memory records). It is a best-effort filter, not a guarantee: a secret in an unrecognised
 * format passes, and the personal-data patterns can over-match (a long digit run may look like a
 * phone number). Findings name the *kind* of match and never echo the matched text.
 */
object SensitiveContentClassifier {
    data class Classification(val sensitivity: Sensitivity, val reasons: List<String>)

    private class Pattern(val kind: String, val regex: Regex)

    private val secretPatterns = listOf(
        Pattern("private key block", Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----")),
        Pattern("provider API key (sk-)", Regex("\\bsk-[A-Za-z0-9_\\-]{16,}")),
        Pattern("provider API key (gsk_)", Regex("\\bgsk_[A-Za-z0-9]{20,}")),
        Pattern("GitHub token", Regex("\\bgh[pousr]_[A-Za-z0-9]{20,}")),
        Pattern("AWS access key id", Regex("\\bAKIA[0-9A-Z]{16}\\b")),
        Pattern("Google API key", Regex("\\bAIza[0-9A-Za-z_\\-]{30,}")),
        Pattern("Slack token", Regex("\\bxox[abprs]-[A-Za-z0-9-]{10,}")),
        Pattern("JWT", Regex("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}")),
        Pattern("bearer token", Regex("(?i)\\bbearer\\s+[A-Za-z0-9._~+/-]{20,}")),
        Pattern(
            "credential assignment",
            Regex("(?i)\\b(password|passwd|pwd|secret|api[_-]?key|access[_-]?token|auth[_-]?token|token)\\b\\s*[:=]\\s*\\S{6,}"),
        ),
    )

    private val personalPatterns = listOf(
        Pattern("email address", Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+\\.[A-Za-z0-9.-]+")),
        Pattern("phone-number-like digits", Regex("(?<![\\w.])\\+?\\d[\\d\\s().-]{8,}\\d(?![\\w.])")),
        Pattern("SSN-like number", Regex("\\b\\d{3}-\\d{2}-\\d{4}\\b")),
    )

    fun classify(text: String): Classification {
        val secrets = secretPatterns.filter { it.regex.containsMatchIn(text) }.map { it.kind }
        if (secrets.isNotEmpty()) return Classification(Sensitivity.SECRET, secrets)
        val personal = personalPatterns.filter { it.regex.containsMatchIn(text) }.map { it.kind }
        if (personal.isNotEmpty()) return Classification(Sensitivity.PERSONAL, personal)
        return Classification(Sensitivity.NORMAL, emptyList())
    }

    /** [text] with every secret match replaced by `[REDACTED:<kind>]`. Personal data is left alone. */
    fun redactSecrets(text: String): String =
        secretPatterns.fold(text) { acc, p -> p.regex.replace(acc, "[REDACTED:${p.kind}]") }
}
