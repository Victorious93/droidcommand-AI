package ai.droidcommand.integration

import ai.droidcommand.agent.ToolSpec
import ai.droidcommand.root.MagiskProvider
import ai.droidcommand.root.RootExecutionTarget
import ai.droidcommand.security.CapabilityId
import ai.droidcommand.security.DefaultExecutionRouter
import ai.droidcommand.security.ExecutionContext
import ai.droidcommand.security.ExecutionRequest
import ai.droidcommand.security.ExecutionTargetType
import ai.droidcommand.security.PrivilegeLevel
import ai.droidcommand.security.RiskTier
import ai.droidcommand.security.RoutingDecision
import ai.droidcommand.security.SecurityPolicy
import ai.droidcommand.security.SecurityPolicyEnforcer
import ai.droidcommand.shell.LocalProcessExecutionTarget
import ai.droidcommand.shell.ProcessBuilderShellExecutor
import ai.droidcommand.shell.ShellSecurityPolicy
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The cross-module integration test named in every `docs/AUDIT_2026-09-05.md`
 * addendum since `RootExecutionTarget` first shipped: proves
 * `core-security.DefaultExecutionRouter` correctly routes between two real
 * `ExecutionTarget` implementations from two independent, sibling modules
 * (`core-shell.LocalProcessExecutionTarget`, `core-root.RootExecutionTarget`)
 * that neither depends on the other — the reason this test could not live in
 * either module, and the reason this module exists at all (see this
 * repository's own scoping discussion for why a dedicated module was chosen
 * over a `testImplementation` edge between the two, or onto `core-security`).
 *
 * **What this test can and cannot prove, stated plainly rather than
 * overclaimed:** [DefaultExecutionRouter.routeExecution] filters
 * `availableTargets` down to `it.type == request.targetType` *before* any
 * least-privilege comparison (confirmed by reading `ExecutionRouter.kt`
 * directly) — and [LocalProcessExecutionTarget]'s type ([ExecutionTargetType.LOCAL_PC])
 * differs from [RootExecutionTarget]'s ([ExecutionTargetType.ANDROID]). So a
 * single `routeExecution` call never actually compares these two targets'
 * privilege levels against each other; whichever type wasn't requested is
 * filtered out immediately. What this test proves instead — the real,
 * previously-untested gap — is that the router, given a heterogeneous list
 * of real targets built from two modules that know nothing about each
 * other, correctly isolates the one matching the request and ignores the
 * other, using two genuine implementations rather than
 * `DefaultExecutionRouterTest`'s scripted `RoutableFakeTarget`.
 */
class RouterCrossModuleIntegrationTest {
    private lateinit var tempDir: File

    @BeforeTest
    fun setUp() {
        tempDir = createTempDirectory("router-cross-module-test").toFile()
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

    private val suScriptAvailable = """
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
    """.trimIndent()

    private fun context(privilegeLevel: PrivilegeLevel) = ExecutionContext(
        workingDir = "/",
        user = "test",
        environment = emptyMap(),
        privilegeLevel = privilegeLevel,
    )

    private val shellCapability = CapabilityId("shell.exec")
    private val rootCapability = CapabilityId("root.shell")

    private fun localTarget(): LocalProcessExecutionTarget {
        val executor = ProcessBuilderShellExecutor(ShellSecurityPolicy(allowedExecutables = setOf("echo")))
        return LocalProcessExecutionTarget("local-1", context(PrivilegeLevel.USER), executor, setOf(shellCapability))
    }

    private fun rootTarget(): RootExecutionTarget {
        val suPath = writeScript("su", suScriptAvailable)
        val provider = MagiskProvider(suExecutable = suPath)
        return RootExecutionTarget("root-1", context(PrivilegeLevel.ROOT), provider, setOf(rootCapability))
    }

    private fun request(capabilityId: CapabilityId, targetType: ExecutionTargetType) = ExecutionRequest(
        capabilityId = capabilityId,
        targetType = targetType,
        parameters = emptyMap(),
        riskTier = RiskTier.READ_ONLY,
    )

    @Test
    fun `a LOCAL_PC request routes to the real shell target and ignores the real root target`() {
        val router = DefaultExecutionRouter()
        val targets = listOf(localTarget(), rootTarget())

        val decision = router.routeExecution(
            request(shellCapability, ExecutionTargetType.LOCAL_PC),
            targets,
            SecurityPolicyEnforcer(SecurityPolicy()),
            testToolSpec(),
        )

        val route = assertIs<RoutingDecision.Route>(decision)
        assertEquals("local-1", route.target.id)

        val result = route.target.execute(listOf("echo", "hello-from-local"))
        assertEquals("hello-from-local\n", result.stdout)
        assertEquals(ExecutionTargetType.LOCAL_PC, result.target)
    }

    @Test
    fun `an ANDROID request routes to the real root target and ignores the real shell target`() {
        val router = DefaultExecutionRouter()
        val targets = listOf(localTarget(), rootTarget())

        val decision = router.routeExecution(
            request(rootCapability, ExecutionTargetType.ANDROID),
            targets,
            SecurityPolicyEnforcer(SecurityPolicy()),
            testToolSpec(),
        )

        val route = assertIs<RoutingDecision.Route>(decision)
        assertEquals("root-1", route.target.id)

        val result = route.target.execute(listOf("echo", "hello-from-root"))
        assertEquals("hello-from-root\n", result.stdout)
        assertEquals(ExecutionTargetType.ANDROID, result.target)
    }

    @Test
    fun `a request for a type neither real target declares yields NoSuitableTarget`() {
        val router = DefaultExecutionRouter()
        val targets = listOf(localTarget(), rootTarget())

        val decision = router.routeExecution(
            request(shellCapability, ExecutionTargetType.DOCKER),
            targets,
            SecurityPolicyEnforcer(SecurityPolicy()),
            testToolSpec(),
        )

        val noTarget = assertIs<RoutingDecision.NoSuitableTarget>(decision)
        assertTrue(noTarget.reason.contains("DOCKER"))
    }

    private fun testToolSpec() = ToolSpec(name = "test_tool", description = "")
}
