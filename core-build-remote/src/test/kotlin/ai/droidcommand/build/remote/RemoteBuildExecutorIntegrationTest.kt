package ai.droidcommand.build.remote

import ai.droidcommand.build.BuildContext
import ai.droidcommand.build.BuildError
import ai.droidcommand.build.BuildExecutionResult
import ai.droidcommand.build.BuildRequest
import ai.droidcommand.build.BuildSecurityPolicy
import ai.droidcommand.build.BuildTarget
import ai.droidcommand.build.ProjectType
import ai.droidcommand.build.SourceLocation
import ai.droidcommand.build.WorkspaceHandle
import ai.droidcommand.remote.JdkHttpTransport
import ai.droidcommand.remote.RemoteEndpoint
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.time.Instant
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipInputStream
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Exercises [RemoteBuildExecutor] against a real local [HttpServer] — a
 * real HTTP round trip, real request/response JSON, and a real ZIP
 * archive built from a real source directory on disk — never a live
 * build service, since none exists to call. This matches the pattern
 * already proven for `AnthropicLlmProviderIntegrationTest` and
 * `OpenAiLlmProviderIntegrationTest`: the JSON shape here is this
 * repository's own, not a vendor's, but the "real server, real bytes over
 * the wire" standard is identical.
 */
class RemoteBuildExecutorIntegrationTest {
    private var server: HttpServer? = null
    private val workspaceRoot = Files.createTempDirectory("core-build-remote-test")
    private val sourceDir = Files.createDirectories(workspaceRoot.resolve("source"))
    private val json = Json { ignoreUnknownKeys = true }

    @AfterTest
    fun tearDown() {
        server?.stop(0)
        workspaceRoot.toFile().deleteRecursively()
    }

    private fun startServer(respond: (RemoteBuildRequest) -> Pair<Int, String>): HttpServer {
        val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server = httpServer
        httpServer.createContext("/builds") { exchange ->
            val requestBody = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            val decoded = json.decodeFromString(RemoteBuildRequest.serializer(), requestBody)
            val (status, responseBody) = respond(decoded)
            val bytes = responseBody.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        httpServer.start()
        return httpServer
    }

    private fun executorFor(httpServer: HttpServer): RemoteBuildExecutor =
        RemoteBuildExecutor(RemoteEndpoint("http://127.0.0.1:${httpServer.address.port}", requireHttps = false), JdkHttpTransport())

    private fun workspace() = WorkspaceHandle(workspaceId = "ws-remote-1", rootPath = workspaceRoot.toString(), createdAt = Instant.now())

    private fun request(securityConstraints: BuildSecurityPolicy = BuildSecurityPolicy()): BuildRequest = BuildRequest(
        sourceLocation = SourceLocation.LocalDirectory(sourceDir.toString()),
        projectType = ProjectType.ANDROID,
        target = BuildTarget.RELEASE,
        variant = "release",
        metadata = mapOf("gradle.task" to "assembleRelease"),
        securityConstraints = securityConstraints,
    )

    private fun unzipEntries(base64: String): Map<String, String> {
        val entries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(Base64.getDecoder().decode(base64))).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                entry = zip.nextEntry
            }
        }
        return entries
    }

    @Test
    fun `sends the real source directory archived as a zip and an ANDROID project type is never refused`() {
        sourceDir.resolve("build.gradle.kts").writeText("android { }")
        val captured = AtomicReference<RemoteBuildRequest>()
        val httpServer = startServer { decoded ->
            captured.set(decoded)
            200 to """{"status":"SUCCESS","exitStatus":0,"output":"BUILD SUCCESSFUL","artifacts":[]}"""
        }

        val result = executorFor(httpServer).execute(BuildContext(request(), workspace(), sourceDir.toString()))

        assertIs<BuildExecutionResult.Success>(result)
        val sent = captured.get()
        assertEquals("ANDROID", sent.projectType)
        assertEquals("RELEASE", sent.target)
        assertEquals("release", sent.variant)
        assertEquals(mapOf("gradle.task" to "assembleRelease"), sent.metadata)
        assertEquals("android { }", unzipEntries(sent.sourceArchiveBase64)["build.gradle.kts"])
    }

    @Test
    fun `a real artifact returned by the server is written to disk with a real checksum`() {
        val content = "PK-fake-apk-bytes".toByteArray()
        val artifactBase64 = Base64.getEncoder().encodeToString(content)
        val httpServer = startServer {
            200 to """{"status":"SUCCESS","exitStatus":0,"output":"ok","artifacts":[{"fileName":"app-release.apk","contentBase64":"$artifactBase64","mimeType":"application/vnd.android.package-archive"}]}"""
        }

        val result = assertIs<BuildExecutionResult.Success>(
            executorFor(httpServer).execute(BuildContext(request(), workspace(), sourceDir.toString())),
        )

        val artifact = result.artifacts.single()
        assertEquals("app-release.apk", artifact.fileName)
        assertEquals(content.size.toLong(), artifact.sizeBytes)
        assertTrue(Files.exists(java.nio.file.Path.of(artifact.path)))
        assertEquals(content.toList(), Files.readAllBytes(java.nio.file.Path.of(artifact.path)).toList())

        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }
        assertEquals(digest, artifact.checksumSha256)
    }

    @Test
    fun `a fileName that tries to escape the workspace is rejected as ArtifactInvalid`() {
        val httpServer = startServer {
            200 to """{"status":"SUCCESS","exitStatus":0,"output":"ok","artifacts":[{"fileName":"../../evil.sh","contentBase64":"ZXZpbA=="}]}"""
        }

        val result = assertIs<BuildExecutionResult.Failure>(
            executorFor(httpServer).execute(BuildContext(request(), workspace(), sourceDir.toString())),
        )

        assertIs<BuildError.ArtifactInvalid>(result.error)
    }

    @Test
    fun `an artifact exceeding maxArtifactBytes is rejected rather than written`() {
        val oversized = Base64.getEncoder().encodeToString(ByteArray(100))
        val httpServer = startServer {
            200 to """{"status":"SUCCESS","exitStatus":0,"output":"ok","artifacts":[{"fileName":"big.bin","contentBase64":"$oversized"}]}"""
        }

        val result = assertIs<BuildExecutionResult.Failure>(
            executorFor(httpServer).execute(
                BuildContext(request(securityConstraints = BuildSecurityPolicy(maxArtifactBytes = 10)), workspace(), sourceDir.toString()),
            ),
        )

        assertIs<BuildError.ArtifactInvalid>(result.error)
    }

    @Test
    fun `a real server-reported build failure maps errorCode to the matching BuildError`() {
        val httpServer = startServer {
            200 to """{"status":"FAILURE","exitStatus":1,"output":"compile error","errorCode":"BUILD_FAILED","errorMessage":"task assembleRelease failed"}"""
        }

        val result = assertIs<BuildExecutionResult.Failure>(
            executorFor(httpServer).execute(BuildContext(request(), workspace(), sourceDir.toString())),
        )

        assertIs<BuildError.BuildFailed>(result.error)
        assertEquals("task assembleRelease failed", result.error.message)
    }

    @Test
    fun `a real 500 response from the server maps to RemoteBuildError`() {
        val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server = httpServer
        httpServer.createContext("/builds") { exchange ->
            exchange.requestBody.readBytes()
            val bytes = "build server on fire".toByteArray()
            exchange.sendResponseHeaders(500, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        httpServer.start()

        val result = assertIs<BuildExecutionResult.Failure>(
            executorFor(httpServer).execute(BuildContext(request(), workspace(), sourceDir.toString())),
        )

        assertIs<BuildError.RemoteBuildError>(result.error)
    }

    @Test
    fun `a malformed response body maps to RemoteBuildError instead of throwing`() {
        val httpServer = startServer { 200 to "not json at all" }

        val result = assertIs<BuildExecutionResult.Failure>(
            executorFor(httpServer).execute(BuildContext(request(), workspace(), sourceDir.toString())),
        )

        assertIs<BuildError.RemoteBuildError>(result.error)
    }

    @Test
    fun `isCancelled true before sending fails as Cancelled without contacting the server`() {
        var contacted = false
        val httpServer = startServer {
            contacted = true
            200 to """{"status":"SUCCESS","artifacts":[]}"""
        }

        val result = assertIs<BuildExecutionResult.Failure>(
            executorFor(httpServer).execute(BuildContext(request(), workspace(), sourceDir.toString())) { true },
        )

        assertIs<BuildError.Cancelled>(result.error)
        assertTrue(!contacted)
    }
}
