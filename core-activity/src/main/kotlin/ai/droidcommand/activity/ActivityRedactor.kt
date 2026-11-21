package ai.droidcommand.activity

/**
 * Turns tool arguments and tool output into short, safe-to-display text *before* it is stored.
 *
 * This is best-effort masking, **not a guarantee**: it hides values under key names that look
 * secret and a handful of well-known token shapes, and it truncates everything. Free text can
 * still contain something it does not recognise. That is why nothing here stores full output,
 * only a short bounded excerpt, and why the feed is in memory only.
 */
object ActivityRedactor {
    const val MASK = "••••"
    private const val MAX_VALUE = 120
    private const val MAX_ARGS = 300
    private const val MAX_OUTPUT = 400

    private val secretKey = Regex(
        "(?i)(pass(word|wd|phrase)?|secret|token|api[_-]?key|apikey|authorization|bearer|credential|private[_-]?key|cookie|session|otp|pin)",
    )

    private val secretShapes = listOf(
        Regex("(?i)bearer\\s+[A-Za-z0-9._~+/=-]{8,}"),
        Regex("sk-[A-Za-z0-9_-]{16,}"),
        Regex("gh[pousr]_[A-Za-z0-9]{20,}"),
        Regex("xox[abprs]-[A-Za-z0-9-]{10,}"),
        Regex("AKIA[0-9A-Z]{16}"),
        Regex("AIza[0-9A-Za-z_-]{30,}"),
        Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?(-----END [A-Z ]*PRIVATE KEY-----|$)"),
        // `name=value` or `name: value` where the name looks secret, inside free text such as a command line.
        Regex("(?i)\\b(pass(word|wd)?|secret|token|api[_-]?key|authorization)\\b\\s*[=:]\\s*\\S+"),
    )

    /** `key=value` pairs, secret-looking keys masked, each value and the whole line bounded. */
    fun args(input: Map<String, String>): String {
        if (input.isEmpty()) return ""
        val line = input.entries.sortedBy { it.key }.joinToString(", ") { (key, value) ->
            val shown = if (secretKey.containsMatchIn(key)) MASK else text(value, MAX_VALUE)
            "$key=$shown"
        }
        return bound(line, MAX_ARGS)
    }

    /** A short excerpt of tool output: secret shapes masked, control characters flattened, bounded. */
    fun output(raw: String): String = text(raw, MAX_OUTPUT)

    /** Masks known secret shapes, collapses whitespace, truncates to [max] characters. */
    fun text(raw: String, max: Int): String {
        var s = raw
        for (shape in secretShapes) s = shape.replace(s, MASK)
        s = s.replace(Regex("\\s+"), " ").trim()
        return bound(s, max)
    }

    private fun bound(s: String, max: Int): String = if (s.length <= max) s else s.take(max - 1) + "…"
}
