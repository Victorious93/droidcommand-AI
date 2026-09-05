package ai.droidcommand.build

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DryRunPlannerTest {
    private lateinit var root: Path

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("droidcommand-dry-run-test")
    }

    @AfterTest
    fun tearDown() {
        deleteRecursively(root)
    }

    private fun request(allowedRoots: List<String> = listOf(root.toString())) = BuildRequest(
        sourceLocation = SourceLocation.LocalDirectory("/tmp/some-project"),
        projectType = ProjectType.ANDROID,
        target = BuildTarget.RELEASE,
        requestedArtifactTypes = setOf(ArtifactType.APK),
        environmentRequirements = setOf(EnvironmentTool.ANDROID_SDK),
        securityConstraints = BuildSecurityPolicy(allowedWorkspaceRoots = allowedRoots),
    )

    @Test
    fun `produces a plan reflecting the request's project type, target, and expected artifacts`() {
        val planner = DryRunPlanner(listOf(root), FixedDetector(ToolAvailability.UNAVAILABLE))
        val plan = planner.plan(request())

        assertEquals(ProjectType.ANDROID, plan.projectType)
        assertEquals(BuildTarget.RELEASE, plan.target)
        assertEquals(setOf(ArtifactType.APK), plan.expectedArtifactTypes)
        assertEquals(7, plan.buildSteps.size)
    }

    @Test
    fun `reflects an unavailable environment tool in the plan rather than hiding it`() {
        val planner = DryRunPlanner(listOf(root), FixedDetector(ToolAvailability.UNAVAILABLE))
        val plan = planner.plan(request())

        val sdkCheck = plan.requiredEnvironment.single { it.tool == EnvironmentTool.ANDROID_SDK }
        assertEquals(ToolAvailability.UNAVAILABLE, sdkCheck.availability)
    }

    @Test
    fun `performs no filesystem mutation`() {
        val before = Files.list(root).use { it.count() }
        val planner = DryRunPlanner(listOf(root), FixedDetector(ToolAvailability.AVAILABLE))

        planner.plan(request())

        val after = Files.list(root).use { it.count() }
        assertEquals(before, after)
    }

    @Test
    fun `references an authorized root in the planned workspace path when one is configured`() {
        val planner = DryRunPlanner(listOf(root), FixedDetector(ToolAvailability.AVAILABLE))
        val plan = planner.plan(request())
        assertTrue(plan.plannedWorkspaceRoot.startsWith(root.toString()))
    }

    @Test
    fun `reports no authorized root without touching the filesystem when none intersects`() {
        val unrelated = Files.createTempDirectory("droidcommand-dry-run-unrelated")
        try {
            val planner = DryRunPlanner(listOf(root), FixedDetector(ToolAvailability.AVAILABLE))
            val plan = planner.plan(request(allowedRoots = listOf(unrelated.toString())))
            assertEquals("NO_AUTHORIZED_ROOT", plan.plannedWorkspaceRoot)
        } finally {
            deleteRecursively(unrelated)
        }
    }

    @Test
    fun `throws for a shape-invalid request rather than producing a misleading plan`() {
        val planner = DryRunPlanner(listOf(root), FixedDetector(ToolAvailability.AVAILABLE))
        val invalid = request().copy(sourceLocation = SourceLocation.LocalDirectory(""))
        assertFailsWith<InvalidBuildRequest> {
            planner.plan(invalid)
        }
    }
}

private class FixedDetector(private val availability: ToolAvailability) : BuildEnvironmentDetector {
    override fun check(tool: EnvironmentTool) = ToolCheckResult(tool, availability)
}
