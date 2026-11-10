package ai.droidcommand.app.assistant

/**
 * Decides what the hands-free voice assistant does with one spoken sentence. Pure Kotlin so it is unit tested.
 *
 * Nothing here runs anything by itself: a note still goes through the Brain "save this?" dialog, a risky
 * request is held behind a confirmation dialog, and everything else is sent to the existing AI chat, where
 * any tool use still asks for approval.
 */
sealed interface JarvisCommand {
    /** "Take a note …" / "Remember that …": drafts a Brain note; the existing save dialog still asks first. */
    data class SaveNote(val title: String, val text: String) : JarvisCommand

    /** "Open notes" / "Open toolbox" …: just switches screens. */
    data class Open(val destination: JarvisDestination) : JarvisCommand

    /** "Stop" / "Cancel" / "Be quiet": stops speaking and listening. */
    data object Stop : JarvisCommand

    /** Looks destructive (delete, wipe, factory reset …): only sent to the AI after the user confirms. */
    data class NeedsConfirmation(val text: String, val reason: String) : JarvisCommand

    /** Everything else: a normal question for the AI assistant. */
    data class Ask(val text: String) : JarvisCommand

    data object Nothing : JarvisCommand
}

enum class JarvisDestination { NOTES, TOOLBOX, BOTS, STATUS, CHAT }

object JarvisCommandRouter {
    private val wakePrefixes = listOf("hey jarvis", "ok jarvis", "okay jarvis", "jarvis", "hey kai", "ok kai", "okay kai", "kai")
    private val notePrefixes = listOf("take a note", "make a note", "new note", "note that", "note", "remember that", "remember")
    private val stopWords = setOf("stop", "cancel", "never mind", "nevermind", "be quiet", "shut up", "quiet")

    /** Words that mean the request may delete, overwrite or reset something. Matched as whole words. */
    val riskyWords: List<String> = listOf(
        "delete", "erase", "wipe", "format", "factory reset", "reset", "uninstall", "remove", "destroy",
        "kill", "reboot", "power off", "shut down", "shutdown", "lock", "root", "flash", "overwrite", "clear all",
        "send money", "pay", "transfer",
    )

    fun route(spoken: String): JarvisCommand {
        val text = stripWakeWord(spoken.trim().trimEnd('.', '!', '?').trim())
        if (text.isBlank()) return JarvisCommand.Nothing
        val lower = text.lowercase()
        if (lower in stopWords) return JarvisCommand.Stop

        openDestination(lower)?.let { return JarvisCommand.Open(it) }

        notePrefixes.firstOrNull { lower == it || lower.startsWith("$it ") || lower.startsWith("$it:") }?.let { prefix ->
            val body = text.substring(prefix.length).trimStart(':', ',', ' ').trim()
            if (body.isNotBlank()) return JarvisCommand.SaveNote(titleFor(body), body)
        }

        riskyWordIn(lower)?.let { word ->
            return JarvisCommand.NeedsConfirmation(text, "This sounds like it could \"$word\" something.")
        }
        return JarvisCommand.Ask(text)
    }

    fun stripWakeWord(text: String): String {
        val lower = text.lowercase()
        val hit = wakePrefixes.firstOrNull { lower == it || lower.startsWith("$it ") || lower.startsWith("$it,") }
            ?: return text
        return text.substring(hit.length).trimStart(',', ' ', '.').trim()
    }

    fun riskyWordIn(lower: String): String? = riskyWords.firstOrNull { word ->
        Regex("(^|[^a-z])" + Regex.escape(word) + "([^a-z]|$)").containsMatchIn(lower)
    }

    private fun openDestination(lower: String): JarvisDestination? {
        val m = Regex("^(open|show|go to|launch)\\s+(my\\s+|the\\s+)?(.+)$").find(lower) ?: return null
        return when (m.groupValues[3].trim()) {
            "notes", "brain", "note", "vault", "notes vault" -> JarvisDestination.NOTES
            "toolbox", "system toolbox", "tools" -> JarvisDestination.TOOLBOX
            "bots", "bot maker", "helper bots", "my bots" -> JarvisDestination.BOTS
            "status", "status tray", "backup", "status and backup" -> JarvisDestination.STATUS
            "chat", "ai chat", "assistant" -> JarvisDestination.CHAT
            else -> null
        }
    }

    private fun titleFor(body: String): String {
        val words = body.split(Regex("\\s+")).take(6).joinToString(" ")
        return if (words.length > 48) words.take(48) else words
    }
}
