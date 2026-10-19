package ai.droidcommand.llm.factory

/**
 * Slash commands that send one message to a named cloud provider: `/claude`, `/chatgpt`, `/gemini`, `/groq`, `/grok`, `/openrouter`.
 *
 * `/claude` reaches Claude through the Anthropic API. It does not start Claude Code: the Claude Code CLI is a desktop
 * tool that this app cannot run or reach from a phone.
 *
 * [parse] only decides what the text means. Text that does not start with a known command is [Parsed.NotCommand] and is
 * sent as an ordinary message, so an unrelated `/path` is never swallowed.
 */
object AiSlashCommands {
    private val WHITESPACE = Regex("\\s+")

    private val providerByCommand: Map<String, String> = linkedMapOf(
        "claude" to "anthropic",
        "chatgpt" to "openai",
        "gemini" to "google",
        "groq" to "groq",
        "grok" to "xai",
        "openrouter" to "openrouter",
    )

    /** Command names without the leading slash, in display order. */
    val commandNames: List<String> = providerByCommand.keys.toList()

    sealed class Parsed {
        /** Not an AI command: send the text as an ordinary message. */
        data object NotCommand : Parsed()

        /** A known command with no prompt after it, such as a bare `/claude`. [command] is lowercase, without the slash. */
        data class MissingPrompt(val command: String) : Parsed()

        /** [providerId] is a [CloudProviderCatalog] id. [prompt] is the text after the command, trimmed and never empty. */
        data class Command(val providerId: String, val prompt: String) : Parsed()
    }

    fun parse(text: String): Parsed {
        val trimmed = text.trim()
        if (!trimmed.startsWith("/")) return Parsed.NotCommand
        val parts = trimmed.substring(1).split(WHITESPACE, limit = 2)
        val command = parts[0].lowercase()
        val providerId = providerByCommand[command] ?: return Parsed.NotCommand
        val prompt = parts.getOrElse(1) { "" }.trim()
        return if (prompt.isEmpty()) Parsed.MissingPrompt(command) else Parsed.Command(providerId, prompt)
    }
}
