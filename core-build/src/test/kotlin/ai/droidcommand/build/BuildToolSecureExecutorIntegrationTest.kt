package ai.droidcommand.build

import ai.droidcommand.agent.AgentStateMachine
import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.ToolExecutor
import ai.droidcommand.agent.ToolRegistry
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.security.ApprovalPrompt
import ai.droidcommand.security.SecureToolExecutor
import ai.droidcommand.security.SecurityPolicy
import ai.droidcommand.security.SecurityPolicyEnforcer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Proves the preferred chain — Agent -> Tool -> Build Service -> Pipeline
 * -> Executor — actually works: [BuildTool] is registered like any other
 * `core-agent.Tool`, gated by `core-security`'s real
 * `SecureToolExecutor`/`SecurityPolicyEnforcer` (not a build-specific
 * reimplementation of approval logic), and only reaches [BuildPipeline] —
 * and therefore only touches the real filesystem — when that policy
 * actually grants approval.
 */
class BuildToolSecureExecutorIntegrationTest {
    private lateinit var root: Path
    private lateinit var sourceDir: Path

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("droidcommand-build-tool-integration-test")
        sourceDir = Files.createTempDirectory("droidcommand-build-tool-integration-source")
        Files.writeString(sourceDir.resolve("pom.xml"), "<!-- fake -->")
    }

    @AfterTest
    fun tearDown() {
        deleteRecursively(root)
        deleteRecursively(sourceDir)
    }

    private fun buildTool(): BuildTool {
        val pipeline = BuildPipeline(
            WorkspaceManager(listOf(root)),
            MockBuildExecutor(),
            object : BuildEnvironmentDetector {
                override fun check(tool: EnvironmentTool) = ToolCheckResult(tool, ToolAvailability.AVAILABLE)
            },
        )
        return BuildTool(pipeline) {
            BuildRequest(
                sourceLocation = SourceLocation.LocalDirectory(sourceDir.toString()),
                projectType = ProjectType.JVM,
                target = BuildTarget.DEBUG,
                securityConstraints = BuildSecurityPolicy(allowedWorkspaceRoots = listOf(root.toString())),
            )
        }
    }

    private fun secureExecutor(approvalPrompt: ApprovalPrompt): Triple<SecureToolExecutor, AgentStateMachine, ToolRegistry> {
        val registry = ToolRegistry().apply { register(buildTool()) }
        val stateMachine = AgentStateMachine()
        val delegate = ToolExecutor(registry, stateMachine, sleep = { })
        val secure = SecureToolExecutor(registry, delegate, stateMachine, SecurityPolicyEnforcer(SecurityPolicy()), approvalPrompt)
        return Triple(secure, stateMachine, registry)
    }

    @Test
    fun `the build tool is SENSITIVE and requires confirmation`() {
        val spec = BuildTool(
            BuildPipeline(
                WorkspaceManager(listOf(root)), MockBuildExecutor(),
                object : BuildEnvironmentDetector {
                    override fun check(tool: EnvironmentTool) = ToolCheckResult(tool, ToolAvailability.AVAILABLE)
                },
            ),
        ) { BuildRequest(sourceLocation = SourceLocation.LocalDirectory("."), projectType = ProjectType.JVM, target = BuildTarget.DEBUG) }.spec

        assertEquals(SecurityLevel.SENSITIVE, spec.securityLevel)
        assertTrue(spec.requiresConfirmation)
    }

    @Test
    fun `when approval is denied, the build pipeline never runs and no workspace is created`() {
        val (secure, _, _) = secureExecutor(ApprovalPrompt { false })
        val before = Files.list(root).use { it.count() }

        val result = secure.run("build_project", emptyMap())

        assertIs<ToolResult.Failure>(result)
        assertTrue((result as ToolResult.Failure).reason.contains("denied"))
        val after = Files.list(root).use { it.count() }
        assertEquals(before, after)
    }

    @Test
    fun `when approval is granted, the build pipeline runs for real and the workspace exists`() {
        val (secure, _, _) = secureExecutor(ApprovalPrompt { true })
        val before = Files.list(root).use { it.count() }

        val result = secure.run("build_project", emptyMap())

        assertIs<ToolResult.Success>(result)
        val after = Files.list(root).use { it.count() }
        assertTrue(after > before)
    }
}
