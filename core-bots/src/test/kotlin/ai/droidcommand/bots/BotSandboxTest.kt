package ai.droidcommand.bots

import ai.droidcommand.agent.ToolResult
import ai.droidcommand.security.AuditEventType
import ai.droidcommand.security.InMemoryAuditLog
import ai.droidcommand.termux.RunCommandContract
import ai.droidcommand.termux.TermuxCommand
import ai.droidcommand.termux.TermuxExecutionResult
import ai.droidcommand.termux.TermuxExecutor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeTermux(var available: Boolean = true) : TermuxExecutor {
    val commands = mutableListOf<TermuxCommand>()
    val containers = mutableSetOf<String>()
    var runResult = TermuxExecutionResult.Success(0, "hello\n", "", 5)

    override fun isAvailable() = available

    override fun execute(command: TermuxCommand, isCancelled: () -> Boolean): TermuxExecutionResult {
        commands += command
        val a = command.args
        return when (a.first()) {
            "list" -> TermuxExecutionResult.Success(0, containers.joinToString("\n"), "", 1)
            "install" -> {
                containers += a[a.indexOf("--name") + 1]
                TermuxExecutionResult.Success(0, "", "", 1)
            }
            "remove" -> {
                containers -= a[1]
                TermuxExecutionResult.Success(0, "", "", 1)
            }
            "login" -> runResult
            else -> TermuxExecutionResult.Failure("unexpected")
        }
    }
}

class BotSandboxTest {
    private val audit = InMemoryAuditLog()
    private val termux = FakeTermux()
    private val executor = ProotSandboxExecutor(termux)
    private var on = true

    private fun run() = SandboxRunTool("bot-1", executor, { on }, audit)
    private fun create() = SandboxCreateTool("bot-1", executor, { on }, audit)
    private fun delete() = SandboxDeleteTool("bot-1", executor, { on }, audit)

    @Test
    fun `sandbox names come only from a safe bot id`() {
        assertEquals("dca-bot-1b2c-3d", SandboxNames.forBot("1b2c-3d"))
        listOf("", "-x", "a b", "a/../b", "a;b", "a".repeat(64), "é").forEach { assertNull(SandboxNames.forBot(it), it) }
    }

    @Test
    fun `commands use the documented proot-distro forms`() {
        assertEquals(listOf("install", "debian:bookworm", "--name", "n"), executor.createCommand("n").args)
        assertEquals(listOf("login", "n", "--isolated", "--", "bash", "-c", "ls -la"), executor.runCommand("n", "ls -la", 1).args)
        assertEquals(listOf("remove", "n"), executor.deleteCommand("n").args)
        assertEquals(listOf("list", "--quiet"), executor.listCommand().args)
        assertEquals(RunCommandContract.PROOT_DISTRO_PATH, executor.runCommand("n", "x", 1).executable)
    }

    @Test
    fun `the command is one argv element, never spliced into a shell string`() {
        val cmd = "echo hi; rm -rf / && \$(whoami)"
        assertEquals(cmd, executor.runCommand("n", cmd, 1).args.last())
    }

    @Test
    fun `an image reference that could be a flag is refused`() {
        assertFailsWith<IllegalArgumentException> { ProotSandboxExecutor(termux, image = "--help") }
        assertFailsWith<IllegalArgumentException> { ProotSandboxExecutor(termux, image = "a b") }
    }

    @Test
    fun `create then run then delete, each audited`() {
        assertEquals(ToolResult.Success("Sandbox created."), create().execute(emptyMap()))
        assertTrue("dca-bot-bot-1" in termux.containers)
        assertEquals(ToolResult.Success("Your sandbox already exists."), create().execute(emptyMap()))
        assertEquals(ToolResult.Success("hello\n"), run().execute(mapOf("command" to "echo hello")))
        assertEquals(ToolResult.Success("Sandbox deleted."), delete().execute(emptyMap()))
        assertTrue(termux.containers.isEmpty())
        val types = audit.all().map { it.type }
        assertEquals(AuditEventType.BOT_SANDBOX_CREATED, types.first())
        assertTrue(AuditEventType.BOT_SANDBOX_COMMAND in types && AuditEventType.BOT_SANDBOX_DELETED in types)
        assertTrue(audit.all().any { it.detail == "run: echo hello" })
    }

    @Test
    fun `run does not create a sandbox by itself`() {
        val r = run().execute(mapOf("command" to "ls"))
        assertIs<ToolResult.Failure>(r)
        assertTrue(termux.commands.none { it.args.first() == "install" || it.args.first() == "login" })
    }

    @Test
    fun `turning the sandbox off stops every tool immediately, before anything is audited or run`() {
        termux.containers += "dca-bot-bot-1"
        on = false
        listOf(create(), run(), delete()).forEach { assertIs<ToolResult.Failure>(it.execute(mapOf("command" to "ls"))) }
        assertTrue(termux.commands.isEmpty())
        assertTrue(audit.all().isEmpty())
    }

    @Test
    fun `no Termux means a plain failure`() {
        termux.available = false
        assertIs<ToolResult.Failure>(run().execute(mapOf("command" to "ls")))
    }

    @Test
    fun `a bot id that cannot name a container never reaches Termux`() {
        val t = SandboxRunTool("bad id; rm", executor, { true }, audit)
        assertIs<ToolResult.Failure>(t.execute(mapOf("command" to "ls")))
        assertTrue(termux.commands.isEmpty())
    }

    @Test
    fun `timeouts are clamped and a non-zero exit is a failure with its output`() {
        termux.containers += "dca-bot-bot-1"
        run().execute(mapOf("command" to "x", "timeoutSeconds" to "99999"))
        assertEquals(300_000, termux.commands.last { it.args.first() == "login" }.timeoutMillis)
        termux.runResult = TermuxExecutionResult.Success(2, "", "boom", 1)
        val r = run().execute(mapOf("command" to "x"))
        assertIs<ToolResult.Failure>(r)
        assertTrue(r.reason.contains("code 2") && r.reason.contains("boom"))
    }

    @Test
    fun `a full audit log stops the command from running`() {
        val tool = SandboxRunTool("bot-1", executor, { true }, InMemoryAuditLog(capacity = 0))
        termux.containers += "dca-bot-bot-1"
        assertIs<ToolResult.Failure>(tool.execute(mapOf("command" to "ls")))
        assertTrue(termux.commands.none { it.args.first() == "login" })
    }

    @Test
    fun `all three tools are sensitive and ask every time`() {
        listOf(create(), run(), delete()).forEach {
            assertTrue(it.spec.requiresConfirmation)
            assertEquals(ai.droidcommand.agent.SecurityLevel.SENSITIVE, it.spec.securityLevel)
        }
    }
}
