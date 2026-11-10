package ai.droidcommand.remotepc

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A command Don saved to paste into a PC terminal. Stored on this phone only; never a password or key. */
@Serializable
data class SavedPcCommand(
    val id: String,
    val label: String,
    val command: String,
)

sealed class SaveCheck {
    data object Ok : SaveCheck()
    data class Refused(val message: String) : SaveCheck()
}

object SavedPcCommands {
    const val MAX_COMMANDS = 50
    const val MAX_LABEL = 60
    const val MAX_COMMAND = 2_000

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(SavedPcCommand.serializer())

    /** Words that usually mean a secret is inside the command. Saving those is refused, not just warned. */
    private val SECRET_HINTS = listOf(
        Regex("""(?i)\bsshpass\b"""),
        Regex("""(?i)\b(password|passwd|pwd|token|secret|api[_-]?key)\s*[=:]"""),
        Regex("""(?i)--(password|passwd|token|secret|api[_-]?key)\b"""),
        Regex("""-----BEGIN [A-Z ]*PRIVATE KEY-----"""),
        Regex("""(?i)\bmysql\b.*\s-p\S+"""),
        Regex("""(?i)https?://[^/\s:@]+:[^/\s@]+@"""),
    )

    fun check(label: String, command: String): SaveCheck {
        if (label.isBlank()) return SaveCheck.Refused("Give the command a short name.")
        if (label.length > MAX_LABEL) return SaveCheck.Refused("Keep the name under $MAX_LABEL letters.")
        if (command.isBlank()) return SaveCheck.Refused("Type the command to save.")
        if (command.length > MAX_COMMAND) return SaveCheck.Refused("That command is too long to save.")
        if (SECRET_HINTS.any { it.containsMatchIn(command) }) {
            return SaveCheck.Refused("That looks like it has a password or key in it. Saved commands must not hold secrets.")
        }
        return SaveCheck.Ok
    }

    fun decode(raw: String?): List<SavedPcCommand> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
            .filter { check(it.label, it.command) == SaveCheck.Ok && it.id.isNotBlank() }
            .distinctBy { it.id }
            .take(MAX_COMMANDS)
    }

    fun encode(commands: List<SavedPcCommand>): String = json.encodeToString(serializer, commands.take(MAX_COMMANDS))
}

/** Steps of running a saved command. Pure so the double-confirm rule is unit-tested. */
sealed class CommandConfirmState {
    data object Idle : CommandConfirmState()

    /** First "Run this?" dialog. Every saved command gets it. */
    data class FirstConfirm(val profile: PcProfile, val command: SavedPcCommand, val risk: CommandRisk) : CommandConfirmState()

    /** Second "Are you sure?" dialog. Only risky commands reach it. */
    data class SecondConfirm(val profile: PcProfile, val command: SavedPcCommand, val risk: CommandRisk) : CommandConfirmState()

    /** Both confirmations done; the caller may now hand the command off. Consumed with [CommandConfirmFlow.consume]. */
    data class Approved(val profile: PcProfile, val command: SavedPcCommand) : CommandConfirmState()
}

class CommandConfirmFlow(private val classifier: (String) -> CommandRisk = RiskyCommandClassifier::classify) {
    var state: CommandConfirmState = CommandConfirmState.Idle
        private set

    fun start(profile: PcProfile, command: SavedPcCommand): CommandConfirmState {
        state = CommandConfirmState.FirstConfirm(profile, command, classifier(command.command))
        return state
    }

    /** Don tapped the confirm button on whichever dialog is showing. */
    fun confirm(): CommandConfirmState {
        state = when (val current = state) {
            is CommandConfirmState.FirstConfirm ->
                if (current.risk.risky) {
                    CommandConfirmState.SecondConfirm(current.profile, current.command, current.risk)
                } else {
                    CommandConfirmState.Approved(current.profile, current.command)
                }
            is CommandConfirmState.SecondConfirm -> CommandConfirmState.Approved(current.profile, current.command)
            // A stray tap with nothing showing, or after approval, never approves anything new.
            else -> current
        }
        return state
    }

    fun cancel(): CommandConfirmState {
        state = CommandConfirmState.Idle
        return state
    }

    /** Returns the approved command once and resets, so a recomposition cannot run it twice. */
    fun consume(): CommandConfirmState.Approved? {
        val approved = state as? CommandConfirmState.Approved ?: return null
        state = CommandConfirmState.Idle
        return approved
    }
}
