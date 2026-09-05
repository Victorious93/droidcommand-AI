package ai.droidcommand.build.remote

import ai.droidcommand.agent.RetryPolicy
import ai.droidcommand.build.Artifact
import ai.droidcommand.build.ArtifactType
import ai.droidcommand.build.BuildContext
import ai.droidcommand.build.BuildError
import ai.droidcommand.build.BuildExecutionResult
import ai.droidcommand.build.BuildExecutor
import ai.droidcommand.remote.HttpTransport
import ai.droidcommand.remote.RemoteClient
import ai.droidcommand.remote.RemoteEndpoint
import ai.droidcommand.remote.RemoteResult
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.net.http.HttpTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A real [BuildExecutor] that delegates the actual build to a remote build
 * server over [RemoteClient] — the same "real capability, so build it for
 * real" reasoning already applied to [ai.droidcommand.remote.JdkHttpTransport]
 * and both LLM providers: sending an HTTP request with the workspace's
 * source archived inside it is a plain JVM/OS capability, not something
 * that needs the Android SDK. Unlike [LocalProcessBuildExecutor]'s
 * `ProjectType.ANDROID` refusal, this executor never rejects a
 * [ai.droidcommand.build.ProjectType] outright — the whole point of
 * delegating to a remote build server is that *it*, not this sandbox, is
 * expected to have the Android Gradle Plugin/SDK. What this executor
 * cannot do is prove that server exists: it has never been run against a
 * real build service, only against a real local test server this
 * repository controls (see `RemoteBuildExecutorIntegrationTest`).
 *
 * The exchange is a single synchronous request/response (see
 * [RemoteBuildRequest]/[RemoteBuildResponse]) — there is no cancellation
 * once the request is in flight, since [HttpTransport] exposes no
 * in-flight cancellation hook; [isCancelled] is only checked before
 * sending.
 */
class RemoteBuildExecutor(
    endpoint: RemoteEndpoint,
    transport: HttpTransport,
    authToken: () -> String? = { null },
    private val retryPolicy: RetryPolicy = RetryPolicy(),
) : BuildExecutor {
    private val remoteClient = RemoteClient(endpoint, transport, authToken)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun execute(context: BuildContext, isCancelled: () -> Boolean): BuildExecutionResult {
        if (isCancelled()) {
            return BuildExecutionResult.Failure(exitStatus = null, output = "", error = BuildError.Cancelled("Build cancelled before it was sent to the remote build server"))
        }

        val archiveBytes = try {
            zipDirectory(Path.of(context.sourceDir))
        } catch (e: Exception) {
            return BuildExecutionResult.Failure(exitStatus = null, output = "", error = BuildError.WorkspaceError("Failed to archive source directory: ${e.message}"))
        }

        val requestDto = RemoteBuildRequest(
            projectType = context.request.projectType.name,
            target = context.request.target.name,
            variant = context.request.variant,
            metadata = context.request.metadata,
            sourceArchiveBase64 = Base64.getEncoder().encodeToString(archiveBytes),
        )

        val requestBody = try {
            json.encodeToString(RemoteBuildRequest.serializer(), requestDto)
        } catch (e: SerializationException) {
            return BuildExecutionResult.Failure(exitStatus = null, output = "", error = BuildError.InvalidRequest(listOf("Failed to encode remote build request: ${e.message}")))
        }

        val result = remoteClient.send(
            path = "builds",
            method = "POST",
            headers = mapOf("content-type" to "application/json"),
            body = requestBody,
            retryPolicy = retryPolicy,
            requestTimeoutMillis = context.request.timeout.toMillis(),
        )

        return when (result) {
            is RemoteResult.Failure -> BuildExecutionResult.Failure(exitStatus = null, output = "", error = classify(result))
            is RemoteResult.Success -> handleResponse(context, result.body)
        }
    }

    private fun handleResponse(context: BuildContext, body: String): BuildExecutionResult {
        val response = try {
            json.decodeFromString(RemoteBuildResponse.serializer(), body)
        } catch (e: SerializationException) {
            return BuildExecutionResult.Failure(exitStatus = null, output = "", error = BuildError.RemoteBuildError("Malformed response from remote build server: ${e.message}"))
        }

        if (response.status != RemoteBuildResponse.STATUS_SUCCESS) {
            return BuildExecutionResult.Failure(
                exitStatus = response.exitStatus,
                output = response.output,
                error = mapErrorCode(response),
            )
        }

        val artifacts = try {
            materializeArtifacts(context, response.artifacts)
        } catch (e: ArtifactMaterializationException) {
            return BuildExecutionResult.Failure(exitStatus = response.exitStatus, output = response.output, error = e.error)
        }

        return BuildExecutionResult.Success(
            exitStatus = response.exitStatus,
            output = response.output,
            artifacts = artifacts,
            warnings = response.warnings,
        )
    }

    private class ArtifactMaterializationException(val error: BuildError) : Exception(error.message)

    /**
     * Writes each server-declared artifact to disk inside the workspace and
     * recomputes its checksum locally rather than trusting anything the
     * server claims about its own output — matching
     * `LocalProcessBuildExecutor`'s "no fabricated metadata" rule. A
     * `fileName` is external input (it came from the network), so it is
     * rejected outright if it could escape the destination directory (a
     * path separator or `..` segment) rather than sanitized/best-effort
     * corrected.
     */
    private fun materializeArtifacts(context: BuildContext, dtos: List<RemoteArtifactDto>): List<Artifact> {
        val destinationDir = Path.of(context.workspace.rootPath).resolve("remote-artifacts").normalize()
        Files.createDirectories(destinationDir)
        val maxArtifactBytes = context.request.securityConstraints.maxArtifactBytes

        return dtos.map { dto ->
            if (dto.fileName.isBlank() || dto.fileName.contains("..") || dto.fileName.contains('/') || dto.fileName.contains('\\')) {
                throw ArtifactMaterializationException(BuildError.ArtifactInvalid("Remote artifact fileName '${dto.fileName}' is not a bare file name"))
            }

            val bytes = try {
                Base64.getDecoder().decode(dto.contentBase64)
            } catch (e: IllegalArgumentException) {
                throw ArtifactMaterializationException(BuildError.ArtifactInvalid("Remote artifact '${dto.fileName}' has invalid base64 content: ${e.message}"))
            }

            if (bytes.size.toLong() > maxArtifactBytes) {
                throw ArtifactMaterializationException(BuildError.ArtifactInvalid("Remote artifact '${dto.fileName}' is ${bytes.size} bytes, exceeding maxArtifactBytes=$maxArtifactBytes"))
            }

            val destination = destinationDir.resolve(dto.fileName)
            Files.write(destination, bytes)

            Artifact(
                artifactId = UUID.randomUUID().toString(),
                buildId = context.workspace.workspaceId,
                type = artifactTypeFor(dto.fileName),
                path = destination.toString(),
                fileName = dto.fileName,
                sizeBytes = bytes.size.toLong(),
                checksumSha256 = sha256Hex(bytes),
                mimeType = dto.mimeType,
                createdAt = Instant.now(),
            )
        }
    }

    private fun mapErrorCode(response: RemoteBuildResponse): BuildError {
        val detail = response.errorMessage ?: "Remote build server reported failure with no error message"
        return when (response.errorCode) {
            "INVALID_REQUEST" -> BuildError.InvalidRequest(listOf(detail))
            "ENVIRONMENT_UNAVAILABLE" -> BuildError.ExecutorUnavailable(detail)
            "TIMEOUT" -> BuildError.Timeout(detail)
            "CANCELLED" -> BuildError.Cancelled(detail)
            "BUILD_FAILED" -> BuildError.BuildFailed(detail)
            else -> BuildError.RemoteBuildError(detail)
        }
    }

    private fun classify(failure: RemoteResult.Failure): BuildError = when {
        failure.statusCode != null && failure.statusCode in 500..599 -> BuildError.RemoteBuildError(failure.reason)
        failure.statusCode != null -> BuildError.InvalidRequest(listOf(failure.reason))
        failure.cause is HttpTimeoutException -> BuildError.Timeout(failure.reason)
        else -> BuildError.RemoteBuildError(failure.reason)
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

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun zipDirectory(sourceDir: Path): ByteArray {
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zip ->
            Files.walk(sourceDir).use { paths ->
                paths.filter { Files.isRegularFile(it) }.forEach { file ->
                    val entryName = sourceDir.relativize(file).toString().replace('\\', '/')
                    zip.putNextEntry(ZipEntry(entryName))
                    Files.newInputStream(file).use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
        return buffer.toByteArray()
    }
}
