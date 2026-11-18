package ai.droidcommand.bots

import ai.droidcommand.agent.PermissionCategory
import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolSpec
import ai.droidcommand.security.AuditEvent
import ai.droidcommand.security.AuditEventType
import ai.droidcommand.security.AuditLog
import ai.droidcommand.termux.RunCommandContract
import ai.droidcommand.termux.TermuxCommand
import ai.droidcommand.termux.TermuxExecutionResult
import ai.droidcommand.termux.TermuxExecutor

sealed class SandboxResult {
    data class Done(val exitCode: Int, val output: String) : SandboxResult()

    data class Failed(val reason: String) : SandboxResult()
}

/** One bot's own Linux userland. A bot gets exactly one, named from its id; it never chooses or sees a name. */
interface SandboxExecutor {
    fun isAvailable(): Boolean

    fun exists(name: String): Boolean

    fun create(name: String): SandboxResult

    fun run(name: String, command: String, timeoutMillis: Long): SandboxResult

    fun delete(name: String): SandboxResult
}

object SandboxNames {
    private val ID = Regex("^[A-Za-z0-9][A-Za-z0-9-]{0,62}$")
    const val PREFIX = "dca-bot-"

    /** `dca-bot-<id>`, or null when [botId] could not be a safe proot-distro container name. */
    fun forBot(botId: String): String? = if (ID.matches(botId)) PREFIX + botId else null
}

/**
 * A [SandboxExecutor] over proot-distro inside Termux/VictorSuite, through the existing [TermuxExecutor]
 * (`RUN_COMMAND`). Each bot is its own proot-distro container, logged into with `--isolated` so the host Termux
 * directories are not mounted into it. Commands use only flags documented in proot-distro's own README
 * (`install IMAGE --name NAME`, `login CONTAINER --isolated -- CMD`, `remove CONTAINER`, `list --quiet`).
 *
 * This is **a separate userland, not a security boundary**: proot is not a VM, and the container runs as the same
 * Android user as Termux. It keeps a bot's files, packages and processes apart from the owner's Termux home and from
 * other bots' containers; it does not stop a determined program from using the network or the container itself.
 * That is why every sandbox tool still asks the owner first.
 *
 * Needs a proot-distro with OCI-image support (the README on its `master` branch). Older releases used distro
 * aliases and `--override-alias`; the commands here are not written for them. Not run on a device or against a real
 * proot-distro in this repo's sessions: the tests below fake [TermuxExecutor] and check the exact argument vectors.
 */
class ProotSandboxExecutor(
    private val termux: TermuxExecutor,
    private val image: String = DEFAULT_IMAGE,
    private val prootDistroPath: String = RunCommandContract.PROOT_DISTRO_PATH,
    private val workdir: String = RunCommandContract.DEFAULT_WORKDIR,
) : SandboxExecutor {
    init {
        require(image.isNotBlank() && !image.startsWith("-") && image.none { it.isWhitespace() }) { "unsafe image reference" }
    }

    override fun isAvailable(): Boolean = termux.isAvailable()

    internal fun listCommand() = cmd(listOf("list", "--quiet"), 30_000)

    internal fun createCommand(name: String) = cmd(listOf("install", image, "--name", name), CREATE_TIMEOUT_MS)

    internal fun runCommand(name: String, command: String, timeoutMillis: Long) =
        cmd(listOf("login", name, "--isolated", "--", "bash", "-c", command), timeoutMillis)

    internal fun deleteCommand(name: String) = cmd(listOf("remove", name), 120_000)

    override fun exists(name: String): Boolean =
        (termux.execute(listCommand()) as? TermuxExecutionResult.Success)?.let { r ->
            r.exitCode == 0 && r.stdout.lineSequence().any { it.trim() == name }
        } ?: false

    override fun create(name: String) = settle(termux.execute(createCommand(name)))

    override fun run(name: String, command: String, timeoutMillis: Long) = settle(termux.execute(runCommand(name, command, timeoutMillis)))

    override fun delete(name: String) = settle(termux.execute(deleteCommand(name)))

    private fun cmd(args: List<String>, timeoutMillis: Long) =
        TermuxCommand(executable = prootDistroPath, args = args, workingDirectory = workdir, timeoutMillis = timeoutMillis)

    private fun settle(r: TermuxExecutionResult): SandboxResult = when (r) {
        is TermuxExecutionResult.Success -> SandboxResult.Done(r.exitCode, r.stdout + if (r.stderr.isBlank()) "" else "\n[stderr]\n${r.stderr}")
        is TermuxExecutionResult.Failure -> SandboxResult.Failed(r.reason)
    }

    companion object {
        /** A plain Debian image; proot-distro's README lists `debian:bookworm`-style references as valid. */
        const val DEFAULT_IMAGE = "debian:bookworm"
        const val CREATE_TIMEOUT_MS = 900_000L
    }
}

/**
 * Shared base for the three sandbox tools. [enabled] is read on **every call**, so turning the sandbox off for the
 * bot (or the Linux-environment gate) takes effect even in the middle of a run. All three are SENSITIVE and ask the
 * owner each time; each is bound to one bot, so no input can point it at another bot's sandbox.
 */
abstract class BotSandboxTool(
    private val botId: String,
    protected val executor: SandboxExecutor,
    private val enabled: () -> Boolean,
    private val auditLog: AuditLog,
) : Tool {
    protected abstract val eventType: AuditEventType

    protected abstract fun detail(input: Map<String, String>): String

    protected abstract fun perform(name: String, input: Map<String, String>): ToolResult

    final override fun execute(input: Map<String, String>): ToolResult {
        if (!enabled()) return ToolResult.Failure("The sandbox is turned off for this bot.")
        val name = SandboxNames.forBot(botId) ?: return ToolResult.Failure("This bot's id cannot name a sandbox.")
        if (!executor.isAvailable()) return ToolResult.Failure("Termux (or VictorSuite) is not available, so there is no sandbox to use.")
        if (!auditLog.record(AuditEvent(eventType, botId, detail(input).take(300)))) {
            return ToolResult.Failure("The activity log is full, so this was not run.")
        }
        return perform(name, input)
    }

    protected fun SandboxResult.toToolResult(okText: String): ToolResult = when (this) {
        is SandboxResult.Failed -> ToolResult.Failure(reason)
        is SandboxResult.Done -> if (exitCode == 0) {
            ToolResult.Success(output.take(MAX_OUTPUT).ifBlank { okText })
        } else {
            ToolResult.Failure("Exited with code $exitCode: ${output.take(MAX_OUTPUT)}")
        }
    }

    companion object {
        const val MAX_OUTPUT = 8_000
    }
}

/** Creates this bot's sandbox (a one-time download of a Linux image). Does nothing if it already exists. */
class SandboxCreateTool(botId: String, executor: SandboxExecutor, enabled: () -> Boolean, auditLog: AuditLog) :
    BotSandboxTool(botId, executor, enabled, auditLog) {
    override val spec = ToolSpec(
        name = BotToolNames.SANDBOX_CREATE,
        description = "Creates your own Linux sandbox if you do not have one yet. Downloads a Linux image the first time.",
        securityLevel = SecurityLevel.SENSITIVE,
        requiresConfirmation = true,
        permissionCategory = PermissionCategory.TERMINAL,
    )
    override val eventType = AuditEventType.BOT_SANDBOX_CREATED

    override fun detail(input: Map<String, String>) = "create sandbox"

    override fun perform(name: String, input: Map<String, String>): ToolResult =
        if (executor.exists(name)) ToolResult.Success("Your sandbox already exists.") else executor.create(name).toToolResult("Sandbox created.")
}

/** Runs one shell command inside this bot's own sandbox. Input: `command`, optional `timeoutSeconds` (1-300, default 60). */
class SandboxRunTool(botId: String, executor: SandboxExecutor, enabled: () -> Boolean, auditLog: AuditLog) :
    BotSandboxTool(botId, executor, enabled, auditLog) {
    override val spec = ToolSpec(
        name = BotToolNames.SANDBOX_RUN,
        description = "Runs one shell command (bash -c) inside your own Linux sandbox. Inputs: 'command' and optional " +
            "'timeoutSeconds' (1-300, default 60). Create the sandbox first with sandbox_create.",
        securityLevel = SecurityLevel.SENSITIVE,
        requiresConfirmation = true,
        permissionCategory = PermissionCategory.TERMINAL,
    )
    override val eventType = AuditEventType.BOT_SANDBOX_COMMAND

    override fun detail(input: Map<String, String>) = "run: ${input["command"].orEmpty()}"

    override fun perform(name: String, input: Map<String, String>): ToolResult {
        val command = input["command"].orEmpty()
        if (command.isBlank()) return ToolResult.Failure("Missing required input 'command'")
        if (command.length > MAX_COMMAND) return ToolResult.Failure("Command is longer than $MAX_COMMAND characters.")
        val seconds = (input["timeoutSeconds"]?.toLongOrNull() ?: DEFAULT_SECONDS).coerceIn(1, MAX_SECONDS)
        if (!executor.exists(name)) return ToolResult.Failure("You have no sandbox yet. Use sandbox_create first.")
        return executor.run(name, command, seconds * 1_000).toToolResult("(no output)")
    }

    companion object {
        const val MAX_COMMAND = 4_000
        const val DEFAULT_SECONDS = 60L
        const val MAX_SECONDS = 300L
    }
}

/** Deletes this bot's sandbox and everything in it. Cannot be undone, so it always asks. */
class SandboxDeleteTool(botId: String, executor: SandboxExecutor, enabled: () -> Boolean, auditLog: AuditLog) :
    BotSandboxTool(botId, executor, enabled, auditLog) {
    override val spec = ToolSpec(
        name = BotToolNames.SANDBOX_DELETE,
        description = "Deletes your own Linux sandbox and everything in it. This cannot be undone.",
        securityLevel = SecurityLevel.SENSITIVE,
        requiresConfirmation = true,
        permissionCategory = PermissionCategory.TERMINAL,
    )
    override val eventType = AuditEventType.BOT_SANDBOX_DELETED

    override fun detail(input: Map<String, String>) = "delete sandbox"

    override fun perform(name: String, input: Map<String, String>): ToolResult =
        if (!executor.exists(name)) ToolResult.Success("You have no sandbox.") else executor.delete(name).toToolResult("Sandbox deleted.")
}
