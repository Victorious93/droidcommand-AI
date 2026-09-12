package ai.droidcommand.root

import ai.droidcommand.agent.AgentStateMachine
import ai.droidcommand.agent.PermissionCategory
import ai.droidcommand.agent.ToolExecutor
import ai.droidcommand.agent.ToolRegistry
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.security.ApprovalPrompt
import ai.droidcommand.security.AuditEventType
import ai.droidcommand.security.AuditLog
import ai.droidcommand.security.InMemoryAuditLog
import ai.droidcommand.security.SecureToolExecutor
import ai.droidcommand.security.SecurityPolicy
import ai.droidcommand.security.SecurityPolicyEnforcer
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Proves the real [MagiskProvider] — not a scripted test double — plugs
 * into the existing, already-tested [SecureToolExecutor]/[SecurityPolicyEnforcer]/
 * [PolicyEnforcingRootExecutor]/[RootTool] stack with zero adapter code,
 * exactly as [RootProvider]'s doc comment claims. This is the same "ROOT
 * TEST MATRIX" [RootToolSecureExecutorIntegrationTest] already proves
 * against a scripted [RootExecutor]; this test proves the identical wiring
 * holds for a genuine [RootProvider] backed by real (fixture) subprocesses.
 */
class MagiskProviderSecureExecutorIntegrationTest {
    private lateinit var tempDir: File

    @BeforeTest
    fun setUp() {
        tempDir = createTempDirectory("magisk-integration-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun writeScript(name: String, content: String): String {
        val file = File(tempDir, name)
        file.writeText(content)
        file.setExecutable(true)
        return file.absolutePath
    }

    private fun secure(
        provider: RootProvider,
        policy: SecurityPolicy,
        approvalPrompt: ApprovalPrompt,
        auditLog: AuditLog? = null,
    ): SecureToolExecutor {
        val registry = ToolRegistry().apply { register(RootTool(provider)) }
        val stateMachine = AgentStateMachine()
        val delegate = ToolExecutor(registry, stateMachine, sleep = { })
        return SecureToolExecutor(registry, delegate, stateMachine, SecurityPolicyEnforcer(policy), approvalPrompt, auditLog = auditLog)
    }

    @Test
    fun `real MagiskProvider reporting root unavailable denies the tool outright, no prompt, no execution`() {
        val provider = MagiskProvider(suExecutable = File(tempDir, "no-such-su").absolutePath)
        var promptCalls = 0

        val result = secure(
            provider,
            SecurityPolicy(rootEnabled = true, rootAvailable = provider::isRootAvailable),
            ApprovalPrompt {
                promptCalls++
                true
            },
        ).run("run_root_command", mapOf("executable" to "id"))

        assertIs<ToolResult.Failure>(result)
        assertTrue(result.reason.contains("unavailable"))
        assertEquals(0, promptCalls)
    }

    @Test
    fun `real MagiskProvider reporting root available, approved, actually executes and audits`() {
        val suPath = writeScript(
            "su",
            """
            #!/bin/sh
            if [ "${'$'}1" = "-c" ]; then
              shift
              if [ "${'$'}1" = "id -u" ]; then
                echo "0"
                exit 0
              fi
              sh -c "${'$'}1"
              exit ${'$'}?
            fi
            exit 1
            """.trimIndent(),
        )
        val provider = MagiskProvider(suExecutable = suPath)
        val auditLog = InMemoryAuditLog()

        val result = secure(
            provider,
            SecurityPolicy(rootEnabled = true, rootAvailable = provider::isRootAvailable, grantedCategories = setOf(PermissionCategory.ROOT)),
            ApprovalPrompt { true },
            auditLog = auditLog,
        ).run("run_root_command", mapOf("executable" to "echo", "args" to "hello"))

        assertIs<ToolResult.Success>(result)
        assertEquals("hello\n", result.output)
        assertTrue(
            auditLog.all().any { it.type == AuditEventType.ACCESS_GRANTED && it.subject == "run_root_command" },
            "a real, audited privileged execution must leave an ACCESS_GRANTED record — same audit path every other Tool already uses, no special-casing for MagiskProvider",
        )
    }

    @Test
    fun `NullRootProvider composes with the same gate, unchanged`() {
        val provider = NullRootProvider()

        val result = secure(
            provider,
            SecurityPolicy(rootEnabled = true, rootAvailable = provider::isRootAvailable),
            ApprovalPrompt { true },
        ).run("run_root_command", mapOf("executable" to "id"))

        assertIs<ToolResult.Failure>(result)
        assertTrue(result.reason.contains("unavailable"))
    }
}
