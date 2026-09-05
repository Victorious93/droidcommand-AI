package ai.droidforge.build.local

import ai.droidforge.build.BuildEnvironmentDetector
import ai.droidforge.build.BuildPipeline
import ai.droidforge.build.BuildRequest
import ai.droidforge.build.BuildResult
import ai.droidforge.build.BuildSecurityPolicy
import ai.droidforge.build.BuildTarget
import ai.droidforge.build.ProjectType
import ai.droidforge.build.SourceLocation
import ai.droidforge.build.ToolCheckResult
import ai.droidforge.build.WorkspaceManager
import ai.droidforge.shell.ProcessBuilderShellExecutor
import ai.droidforge.shell.ShellSecurityPolicy
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Runs a real `javac` build all the way through `core-build.BuildPipeline`
 * — workspace creation, real source import, the real executor, and the
 * pipeline's own artifact-containment validation — not just
 * [LocalProcessBuildExecutor] in isolation. Proves the two modules
 * actually compose, the same standard `BuildToolSecureExecutorIntegrationTest`
 * already holds `MockBuildExecutor` to, but with a real compiler this time.
 */
class LocalProcessBuildExecutorPipelineIntegrationTest {
    private val authorizedRoot = Files.createTempDirectory("core-build-local-pipeline-test")
    private val originalSourceDir = Files.createDirectories(Files.createTempDirectory("core-build-local-pipeline-src"))

    @AfterTest
    fun tearDown() {
        authorizedRoot.toFile().deleteRecursively()
        originalSourceDir.toFile().deleteRecursively()
    }

    private object NoRequirementsDetector : BuildEnvironmentDetector {
        override fun check(tool: ai.droidforge.build.EnvironmentTool): ToolCheckResult =
            ToolCheckResult(tool, ai.droidforge.build.ToolAvailability.AVAILABLE, null)
    }

    @Test
    fun `a real javac build runs end to end through BuildPipeline and produces a validated artifact`() {
        originalSourceDir.resolve("Hello.java").writeText("public class Hello { public static void main(String[] a) {} }")

        val workspaceManager = WorkspaceManager(authorizedRoots = listOf(authorizedRoot))
        val shellExecutor = ProcessBuilderShellExecutor(
            ShellSecurityPolicy(allowedExecutables = setOf("javac"), allowedWorkingDirectories = listOf(authorizedRoot.toString())),
        )
        val pipeline = BuildPipeline(workspaceManager, LocalProcessBuildExecutor(shellExecutor), NoRequirementsDetector)

        val request = BuildRequest(
            sourceLocation = SourceLocation.LocalDirectory(originalSourceDir.toString()),
            projectType = ProjectType.JVM,
            target = BuildTarget.ARTIFACT,
            securityConstraints = BuildSecurityPolicy(allowedWorkspaceRoots = listOf(authorizedRoot.toString())),
            metadata = mapOf("command.executable" to "javac", "command.args" to "Hello.java", "artifact.paths" to "Hello.class"),
        )

        val result = assertIs<BuildResult.Success>(pipeline.execute(request))

        assertEquals(1, result.artifacts.size)
        val artifact = result.artifacts.single()
        assertEquals("Hello.class", artifact.fileName)
        assertEquals(Files.size(java.nio.file.Path.of(artifact.path)), artifact.sizeBytes)
    }
}
