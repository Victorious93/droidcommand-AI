package ai.droidcommand.build.local

import ai.droidcommand.build.ArtifactType
import ai.droidcommand.build.BuildContext
import ai.droidcommand.build.BuildError
import ai.droidcommand.build.BuildExecutionResult
import ai.droidcommand.build.BuildRequest
import ai.droidcommand.build.BuildTarget
import ai.droidcommand.build.ProjectType
import ai.droidcommand.build.SourceLocation
import ai.droidcommand.build.WorkspaceHandle
import ai.droidcommand.shell.ProcessBuilderShellExecutor
import ai.droidcommand.shell.ShellCommand
import ai.droidcommand.shell.ShellExecutionResult
import ai.droidcommand.shell.ShellExecutor
import ai.droidcommand.shell.ShellSecurityPolicy
import java.nio.file.Files
import java.time.Instant
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [LocalProcessBuildExecutor] is exercised here against a real
 * `javac` invocation — not a scripted fake — proving this executor
 * genuinely compiles real source and reports a real artifact, the same
 * "prove it against reality, not a double" standard `core-shell` and
 * `core-remote` were held to. `javac`/`jar` are plain JDK tools present in
 * any JVM environment, including this sandbox with no Android SDK.
 */
class LocalProcessBuildExecutorTest {
    private val workspaceRoot = Files.createTempDirectory("core-build-local-test")
    private val sourceDir = Files.createDirectories(workspaceRoot.resolve("source"))

    @AfterTest
    fun tearDown() {
        workspaceRoot.toFile().deleteRecursively()
    }

    private fun workspace() = WorkspaceHandle(workspaceId = "ws-1", rootPath = workspaceRoot.toString(), createdAt = Instant.now())

    private fun request(projectType: ProjectType = ProjectType.JVM, metadata: Map<String, String> = emptyMap()): BuildRequest = BuildRequest(
        sourceLocation = SourceLocation.LocalDirectory(sourceDir.toString()),
        projectType = projectType,
        target = BuildTarget.ARTIFACT,
        metadata = metadata,
    )

    private fun realShellExecutor(vararg allowedExecutables: String): ShellExecutor =
        ProcessBuilderShellExecutor(ShellSecurityPolicy(allowedExecutables = allowedExecutables.toSet(), allowedWorkingDirectories = listOf(sourceDir.toString())))

    @Test
    fun `a real javac invocation compiles source and reports a real, checksummed artifact`() {
        sourceDir.resolve("Hello.java").writeText("public class Hello { public static void main(String[] a) {} }")

        val executor = LocalProcessBuildExecutor(realShellExecutor("javac"))
        val context = BuildContext(
            request(metadata = mapOf("command.executable" to "javac", "command.args" to "Hello.java", "artifact.paths" to "Hello.class")),
            workspace(),
            sourceDir.toString(),
        )

        val result = assertIs<BuildExecutionResult.Success>(executor.execute(context))

        assertEquals(1, result.artifacts.size)
        val artifact = result.artifacts.single()
        assertEquals("Hello.class", artifact.fileName)
        assertEquals(ArtifactType.BINARY, artifact.type)
        assertEquals(Files.size(sourceDir.resolve("Hello.class")), artifact.sizeBytes)
        assertTrue(artifact.checksumSha256.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `ANDROID projectType is refused outright without ever invoking the shell executor`() {
        var shellCalls = 0
        val scriptedShell = ShellExecutor { _, _ -> shellCalls++; ShellExecutionResult.Success(0, "", "", 0) }
        val executor = LocalProcessBuildExecutor(scriptedShell)

        val context = BuildContext(request(projectType = ProjectType.ANDROID, metadata = mapOf("command.executable" to "javac")), workspace(), sourceDir.toString())
        val result = assertIs<BuildExecutionResult.Failure>(executor.execute(context))

        assertIs<BuildError.ExecutorUnavailable>(result.error)
        assertTrue(result.error.message.contains("ANDROID"))
        assertEquals(0, shellCalls)
    }

    @Test
    fun `a missing command_executable fails without invoking the shell executor`() {
        var shellCalls = 0
        val scriptedShell = ShellExecutor { _, _ -> shellCalls++; ShellExecutionResult.Success(0, "", "", 0) }
        val executor = LocalProcessBuildExecutor(scriptedShell)

        val context = BuildContext(request(), workspace(), sourceDir.toString())
        val result = assertIs<BuildExecutionResult.Failure>(executor.execute(context))

        assertIs<BuildError.ExecutorUnavailable>(result.error)
        assertEquals(0, shellCalls)
    }

    @Test
    fun `a real non-zero exit code becomes BuildError BuildFailed carrying real stderr`() {
        sourceDir.resolve("Broken.java").writeText("this is not valid java")

        val executor = LocalProcessBuildExecutor(realShellExecutor("javac"))
        val context = BuildContext(request(metadata = mapOf("command.executable" to "javac", "command.args" to "Broken.java")), workspace(), sourceDir.toString())

        val result = assertIs<BuildExecutionResult.Failure>(executor.execute(context))

        assertIs<BuildError.BuildFailed>(result.error)
        assertTrue(result.output.isNotBlank())
    }

    @Test
    fun `a declared artifact the build never produced fails as ArtifactNotFound`() {
        sourceDir.resolve("Hello.java").writeText("public class Hello { public static void main(String[] a) {} }")

        val executor = LocalProcessBuildExecutor(realShellExecutor("javac"))
        val context = BuildContext(
            request(metadata = mapOf("command.executable" to "javac", "command.args" to "Hello.java", "artifact.paths" to "DoesNotExist.class")),
            workspace(),
            sourceDir.toString(),
        )

        val result = assertIs<BuildExecutionResult.Failure>(executor.execute(context))
        assertIs<BuildError.ArtifactNotFound>(result.error)
    }

    @Test
    fun `a declared artifact path escaping the workspace root fails as ArtifactInvalid rather than being reported`() {
        val outside = Files.createTempFile("outside-artifact", ".txt")
        try {
            val relativeEscape = sourceDir.relativize(outside)

            val executor = LocalProcessBuildExecutor(realShellExecutor("true"))
            val context = BuildContext(
                request(metadata = mapOf("command.executable" to "true", "artifact.paths" to relativeEscape.toString())),
                workspace(),
                sourceDir.toString(),
            )

            val result = assertIs<BuildExecutionResult.Failure>(executor.execute(context))
            assertIs<BuildError.ArtifactInvalid>(result.error)
        } finally {
            Files.deleteIfExists(outside)
        }
    }

    @Test
    fun `a real ShellCommand not in the allow-list is rejected before any process starts`() {
        sourceDir.resolve("Hello.java").writeText("public class Hello { public static void main(String[] a) {} }")

        val executor = LocalProcessBuildExecutor(realShellExecutor("some-other-tool"))
        val context = BuildContext(request(metadata = mapOf("command.executable" to "javac", "command.args" to "Hello.java")), workspace(), sourceDir.toString())

        val result = assertIs<BuildExecutionResult.Failure>(executor.execute(context))
        assertIs<BuildError.ExecutorUnavailable>(result.error)
        assertTrue((result.error as BuildError.ExecutorUnavailable).message.contains("not in the allowed"))
    }
}

private fun ShellExecutor(block: (ShellCommand, () -> Boolean) -> ShellExecutionResult): ShellExecutor = object : ShellExecutor {
    override fun execute(command: ShellCommand, isCancelled: () -> Boolean): ShellExecutionResult = block(command, isCancelled)
}
