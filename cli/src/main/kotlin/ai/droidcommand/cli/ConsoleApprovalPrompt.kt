package ai.droidcommand.cli

import ai.droidcommand.security.ApprovalPrompt

/**
 * The real, interactive [ApprovalPrompt] this entrypoint uses by default: prints the tool name and
 * its exact input (not just a generic reason) to stdout and reads a `y`/`n` line from stdin. Any
 * input other than an exact `y` (case-insensitive) — including a blank line or EOF — is treated as
 * a denial, matching the fail-closed posture every other default in this module takes.
 *
 * Input values can be LLM-authored (Forge mode), so they are printed through [escapeForDisplay]:
 * control characters (newlines, ANSI escapes) are made visible instead of interpreted, so a crafted
 * value cannot forge a fake "Approve?" line or hide the real command, and very long values are
 * truncated with their true length shown.
 */
object ConsoleApprovalPrompt : ApprovalPrompt {
    private const val MAX_VALUE_CHARS = 500

    // Control chars, line/paragraph separators, and format chars (zero-width, bidi overrides such as
    // U+202E) — everything that can hide, reorder, or break up displayed text.
    private val INVISIBLE_CATEGORIES = setOf(
        CharCategory.CONTROL,
        CharCategory.FORMAT,
        CharCategory.LINE_SEPARATOR,
        CharCategory.PARAGRAPH_SEPARATOR,
    )

    override fun requestApproval(reason: String): Boolean = ask(reason)

    override fun requestApproval(toolName: String, input: Map<String, String>, reason: String): Boolean =
        ask(describe(toolName, input, reason))

    internal fun describe(toolName: String, input: Map<String, String>, reason: String): String = buildString {
        append(escapeForDisplay(reason))
        append("\n  tool: ").append(escapeForDisplay(toolName))
        if (input.isEmpty()) {
            append("\n  input: (none)")
        } else {
            append("\n  input:")
            input.entries.sortedBy { it.key }.forEach { (key, value) ->
                append("\n    ").append(escapeForDisplay(key)).append(" = ").append(escapeForDisplay(value))
            }
        }
    }

    internal fun escapeForDisplay(text: String): String {
        val shown = if (text.length > MAX_VALUE_CHARS) text.take(MAX_VALUE_CHARS) else text
        val escaped = buildString {
            for (ch in shown) {
                when {
                    ch == '\n' -> append("\\n")
                    ch == '\r' -> append("\\r")
                    ch == '\t' -> append("\\t")
                    ch.category in INVISIBLE_CATEGORIES -> append("\\u%04x".format(ch.code))
                    else -> append(ch)
                }
            }
        }
        return if (text.length > MAX_VALUE_CHARS) "$escaped… (+${text.length - MAX_VALUE_CHARS} more chars)" else escaped
    }

    private fun ask(message: String): Boolean {
        print("$message\nApprove? [y/N]: ")
        System.out.flush()
        return readlnOrNull()?.trim()?.equals("y", ignoreCase = true) == true
    }
}
