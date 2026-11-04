package ai.droidcommand.build.local

import ai.droidcommand.build.Artifact
import ai.droidcommand.build.ArtifactType
import ai.droidcommand.build.BuildContext
import ai.droidcommand.build.BuildError
import ai.droidcommand.build.BuildExecutionResult
import ai.droidcommand.build.BuildExecutor
import ai.droidcommand.build.ProjectType
import ai.droidcommand.shell.ShellCommand
import ai.droidcommand.shell.ShellExecutionResult
import ai.droidcommand.shell.ShellExecutor
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

data class LocalBuildCommand(val executable: String, val args: List<String> = emptyList())

/**
 * A real [BuildExecutor] for [ProjectType.JVM]/[ProjectType.NATIVE]/
 * [ProjectType.GENERIC] builds — spawning a build command (`javac`,
 * `./gradlew`, `make`, ...) is a plain JVM/OS capability, not something
 * that needs the Android SDK, the same "real capability, so build it for
 * real" reasoning that made `core-shell`'s subprocess executor real
 * rather than mocked. [ProjectType.ANDROID] is refused outright — that
 * needs the Android Gradle Plugin and Android SDK, which this executor
 * does not provide and never pretends to.
 *
 * Never invents a command from [ProjectType]: the actual build command
 * comes entirely from [ai.droidcommand.build.BuildRequest.metadata]
 * (`command.executable`, optionally `command.args` space-separated) — this
 * executor has no opinion on what "building a JVM project" means and does
 * not guess at one. It delegates the actual process spawn to
 * [ShellExecutor], reusing `core-shell`'s already-real, already-tested,
 * fail-closed subprocess execution rather than reimplementing
 * [ProcessBuilder] handling here.
 */
class LocalProcessBuildExecutor(private val shellExecutor: ShellExecutor) : BuildExecutor {
    override fun execute(context: BuildContext, isCancelled: () -> Boolean): BuildExecutionResult {
        if (context.request.projectType == ProjectType.ANDROID) {
            return BuildExecutionResult.Failure(
                exitStatus = null,
                output = "",
                error = BuildError.ExecutorUnavailable(
                    "LocalProcessBuildExecutor does not support ANDROID builds — that needs the Android Gradle Plugin and Android SDK, which this executor does not provide",
                ),
            )
        }

        val command = resolveCommand(context.request.metadata)
            ?: return BuildExecutionResult.Failure(
                exitStatus = null,
                output = "",
                error = BuildError.ExecutorUnavailable("No build command configured: request.metadata must set 'command.executable'"),
            )

        val shellCommand = ShellCommand(
            executable = command.executable,
            args = command.args,
            workingDirectory = context.sourceDir,
            timeoutMillis = context.request.timeout.toMillis(),
        )

        val shellResult = shellExecutor.execute(shellCommand, isCancelled)
        val (exitCode, stdout, stderr) = when (shellResult) {
            is ShellExecutionResult.Failure ->
                return BuildExecutionResult.Failure(exitStatus = null, output = "", error = classifyShellFailure(shellResult))
            is ShellExecutionResult.Success -> Triple(shellResult.exitCode, shellResult.stdout, shellResult.stderr)
        }

        val output = if (stderr.isBlank()) stdout else "$stdout\n$stderr"
        if (exitCode != 0) {
            return BuildExecutionResult.Failure(
                exitStatus = exitCode,
                output = output,
                error = BuildError.BuildFailed("Command '${command.executable}' exited with code $exitCode"),
            )
        }

        val artifacts = try {
            collectArtifacts(context)
        } catch (e: ArtifactCollectionException) {
            return BuildExecutionResult.Failure(exitStatus = exitCode, output = output, error = e.error)
        }

        return BuildExecutionResult.Success(exitStatus = exitCode, output = output, artifacts = artifacts)
    }

    private fun resolveCommand(metadata: Map<String, String>): LocalBuildCommand? {
        val executable = metadata["command.executable"]?.takeIf { it.isNotBlank() } ?: return null
        val args = metadata["command.args"]?.split(" ")?.filter { it.isNotEmpty() } ?: emptyList()
        return LocalBuildCommand(executable, args)
    }

    private fun classifyShellFailure(failure: ShellExecutionResult.Failure): BuildError = when {
        failure.reason.contains("cancelled", ignoreCase = true) -> BuildError.Cancelled(failure.reason)
        failure.reason.contains("timed out", ignoreCase = true) -> BuildError.Timeout(failure.reason)
        else -> BuildError.ExecutorUnavailable(failure.reason)
    }

    private class ArtifactCollectionException(val error: BuildError) : Exception(error.message)

    /**
     * Discovers artifacts the build command produced, from paths the
     * caller explicitly declared in `request.metadata["artifact.paths"]`
     * (comma-separated, relative to [BuildContext.sourceDir]) — this
     * executor never guesses at "likely" build outputs by scanning the
     * workspace, since that would be fabricating structure the caller
     * never actually described.
     */
    private fun collectArtifacts(context: BuildContext): List<Artifact> {
        val declaredPaths = context.request.metadata["artifact.paths"]
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

        val workspaceRoot = Path.of(context.workspace.rootPath).normalize()
        val sourceDir = Path.of(context.sourceDir)

        return declaredPaths.map { relativePath ->
            val path = sourceDir.resolve(relativePath).normalize()
            if (!path.startsWith(workspaceRoot)) {
                throw ArtifactCollectionException(BuildError.ArtifactInvalid("Declared artifact path '$relativePath' resolves outside the workspace root"))
            }
            if (!Files.exists(path)) {
                throw ArtifactCollectionException(BuildError.ArtifactNotFound("Declared artifact '$relativePath' was not produced by the build command"))
            }

            Artifact(
                artifactId = UUID.randomUUID().toString(),
                // BuildContext carries no pipeline-level buildId (only BuildPipeline
                // generates one, after the executor returns) — workspaceId is the
                // closest correlated id available here, not a stand-in for the real
                // buildId that ends up in BuildResult.Success.
                buildId = context.workspace.workspaceId,
                type = artifactTypeFor(path.fileName.toString()),
                path = path.toString(),
                fileName = path.fileName.toString(),
                sizeBytes = Files.size(path),
                checksumSha256 = sha256Hex(path),
                createdAt = Instant.now(),
            )
        }
    }

    private fun artifactTypeFor(fileName: String): ArtifactType = when (fileName.substringAfterLast('.', "").lowercase()) {
        "apk" -> ArtifactType.APK
        "aab" -> ArtifactType.AAB
        "jar" -> ArtifactType.JAR
        "zip" -> ArtifactType.ZIP
        "tar" -> ArtifactType.TAR
        "log" -> ArtifactType.LOG
        else -> ArtifactType.BINARY
    }

    private fun sha256Hex(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { stream ->
            val buffer = ByteArray(8192)
            var read: Int
            while (stream.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
