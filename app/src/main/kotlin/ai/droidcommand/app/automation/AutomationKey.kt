package ai.droidcommand.app.automation

import ai.droidcommand.agent.Macro
import ai.droidcommand.agent.MacroStep
import ai.droidcommand.llm.factory.CloudProviderCatalog
import java.net.URLDecoder
import java.net.URLEncoder

/** What an automation key does when it runs. Both kinds wait for the owner's approval; see [AutomationRunner]. */
sealed class AutomationAction {
    /** Runs [executable] with [args], split on spaces the way the shell tool splits them. [executable] must be allowed. */
    data class Shell(val executable: String, val args: String) : AutomationAction()

    /** Sends [prompt] to [providerId], a [CloudProviderCatalog] id, as one request. */
    data class Ai(val providerId: String, val prompt: String) : AutomationAction()
}

/** A saved key. [label] is what the screen shows; [id] is random and never shown. */
data class AutomationKey(val id: String, val label: String, val action: AutomationAction) {
    /** A one-step macro, so every key runs through the same `MacroExecutor` as any other saved routine. */
    fun toMacro(): Macro = Macro(name = id, steps = listOf(toStep()))

    private fun toStep(): MacroStep = when (action) {
        is AutomationAction.Shell ->
            MacroStep(AutomationShellTool.NAME, mapOf("executable" to action.executable, "args" to action.args))
        is AutomationAction.Ai ->
            MacroStep(AiPromptTool.NAME, mapOf("provider" to action.providerId, "prompt" to action.prompt))
    }
}

/**
 * One registry line per key: five percent-encoded fields joined by tabs. A shell key stores its executable and
 * arguments; an AI key stores its provider id and prompt. Pure, so it is JVM-tested. Lines that do not decode are dropped.
 */
object AutomationKeyCodec {
    private const val SEPARATOR = "\t"
    private const val FIELD_COUNT = 5
    private const val KIND_SHELL = "shell"
    private const val KIND_AI = "ai"
    private val UTF8 = Charsets.UTF_8.name()

    fun encode(key: AutomationKey): String {
        val (kind, first, second) = when (val action = key.action) {
            is AutomationAction.Shell -> Triple(KIND_SHELL, action.executable, action.args)
            is AutomationAction.Ai -> Triple(KIND_AI, action.providerId, action.prompt)
        }
        return listOf(key.id, key.label, kind, first, second).joinToString(SEPARATOR) { URLEncoder.encode(it, UTF8) }
    }

    /** Null for any line that is not a complete, valid record. */
    fun decode(line: String): AutomationKey? {
        val fields = line.split(SEPARATOR)
        if (fields.size != FIELD_COUNT) return null
        val decoded = try {
            fields.map { URLDecoder.decode(it, UTF8) }
        } catch (_: IllegalArgumentException) {
            // An invalid percent-escape.
            return null
        }
        val (id, label, kind, first, second) = decoded
        if (id.isBlank() || label.isBlank()) return null
        val action = when (kind) {
            KIND_SHELL -> if (first.isBlank()) null else AutomationAction.Shell(first, second)
            KIND_AI -> if (second.isBlank() || CloudProviderCatalog.byId(first) == null) null else AutomationAction.Ai(first, second)
            else -> null
        } ?: return null
        return AutomationKey(id, label, action)
    }
}
