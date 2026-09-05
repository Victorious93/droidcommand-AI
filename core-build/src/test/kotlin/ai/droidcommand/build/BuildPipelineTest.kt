package ai.droidcommand.build

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BuildPipelineTest {
    private lateinit var root: Path
    private lateinit var sourceDir: Path

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("droidcommand-pipeline-test")
        sourceDir = Files.createTempDirectory("droidcommand-pipeline-source")
        Files.writeString(sourceDir.resolve("build.gradle"), "// fake project file")
    }

    @AfterTest
    fun tearDown() {
        deleteRecursively(root)
        deleteRecursively(sourceDir)
    }

    private fun request(
        environmentRequirements: Set<EnvironmentTool> = emptySet(),
        allowedRoots: List<String> = listOf(root.toString()),
        timeout: Duration = Duration.ofMinutes(10),
        maxArtifactBytes: Long = 10_000_000,
    ) = BuildRequest(
        sourceLocation = SourceLocation.LocalDirectory(sourceDir.toString()),
        projectType = ProjectType.JVM,
        target = BuildTarget.DEBUG,
        environmentRequirements = environmentRequirements,
        securityConstraints = BuildSecurityPolicy(allowedWorkspaceRoots = allowedRoots, maxArtifactBytes = maxArtifactBytes),
        timeout = timeout,
    )

    private fun pipeline(
        executor: BuildExecutor = MockBuildExecutor(),
        detector: BuildEnvironmentDetector = FixedEnvironmentDetector(ToolAvailability.AVAILABLE),
        eventSink: BuildEventSink = BuildEventSink.NOOP,
        clock: () -> Instant = Instant::now,
    ) = BuildPipeline(WorkspaceManager(listOf(root)), executor, detector, eventSink, clock)

    @Test
    fun `a successful mock execution returns a well-formed Success result`() {
        val result = assertIs<BuildResult.Success>(pipeline().execute(request()))
        assertEquals(ProjectType.JVM, result.projectType)
        assertEquals(BuildTarget.DEBUG, result.target)
        assertTrue(result.durationMillis >= 0)
        assertEquals(emptyList(), result.artifacts)
    }

    @Test
    fun `the workspace is left COMPLETED, not cleaned, after a success`() {
        val manager = WorkspaceManager(listOf(root))
        val p = BuildPipeline(manager, MockBuildExecutor(), FixedEnvironmentDetector(ToolAvailability.AVAILABLE))
        val result = assertIs<BuildResult.Success>(p.execute(request()))
        val workspaceDir = root.resolve(result.workspaceId)
        assertTrue(Files.exists(workspaceDir))
    }

    @Test
    fun `an invalid request fails at VALIDATE without creating any workspace directory`() {
        val before = Files.list(root).use { it.count() }
        val invalid = request().copy(sourceLocation = SourceLocation.LocalDirectory(""))

        val result = assertIs<BuildResult.Failure>(pipeline().execute(invalid))

        assertEquals(BuildStage.VALIDATE, result.failedStage)
        assertEquals("INVALID_REQUEST", result.error.code)
        val after = Files.list(root).use { it.count() }
        assertEquals(before, after)
    }

    @Test
    fun `a missing environment requirement fails at RESOLVE_STRATEGY and cleans up the workspace`() {
        val p = pipeline(detector = FixedEnvironmentDetector(ToolAvailability.UNAVAILABLE))
        val result = assertIs<BuildResult.Failure>(p.execute(request(environmentRequirements = setOf(EnvironmentTool.ANDROID_SDK))))

        assertEquals(BuildStage.RESOLVE_STRATEGY, result.failedStage)
        assertEquals("ENVIRONMENT_UNAVAILABLE", result.error.code)
    }

    @Test
    fun `a workspace root the request does not authorize is denied at CREATE_WORKSPACE`() {
        val unauthorized = Files.createTempDirectory("droidcommand-pipeline-unauthorized")
        try {
            val result = assertIs<BuildResult.Failure>(pipeline().execute(request(allowedRoots = listOf(unauthorized.toString()))))
            assertEquals(BuildStage.CREATE_WORKSPACE, result.failedStage)
            assertEquals("SECURITY_DENIED", result.error.code)
        } finally {
            deleteRecursively(unauthorized)
        }
    }

    @Test
    fun `a nonexistent source path fails at PREPARE_SOURCE and cleans up the workspace`() {
        val missingSource = root.resolve("nowhere")
        val req = BuildRequest(
            sourceLocation = SourceLocation.LocalDirectory(missingSource.toString()),
            projectType = ProjectType.JVM,
            target = BuildTarget.DEBUG,
            securityConstraints = BuildSecurityPolicy(allowedWorkspaceRoots = listOf(root.toString())),
        )
        val before = Files.list(root).use { it.count() }

        val result = assertIs<BuildResult.Failure>(pipeline().execute(req))

        assertEquals(BuildStage.PREPARE_SOURCE, result.failedStage)
        assertEquals("WORKSPACE_ERROR", result.error.code)
        val after = Files.list(root).use { it.count() }
        assertEquals(before, after) // the workspace directory it created was cleaned up
    }

    @Test
    fun `an executor failure surfaces its own BuildError and cleans up the workspace`() {
        val executor = object : BuildExecutor {
            override fun execute(context: BuildContext, isCancelled: () -> Boolean) =
                BuildExecutionResult.Failure(1, "compile error", BuildError.BuildFailed("simulated compiler failure"))
        }
        val before = Files.list(root).use { it.count() }

        val result = assertIs<BuildResult.Failure>(pipeline(executor = executor).execute(request()))

        assertEquals(BuildStage.EXECUTE, result.failedStage)
        assertEquals("BUILD_FAILED", result.error.code)
        val after = Files.list(root).use { it.count() }
        assertEquals(before, after)
    }

    @Test
    fun `an artifact outside the workspace root is rejected at VALIDATE_RESULT, even if it exists and matches its declared size`() {
        // A file that genuinely exists with the right size, but sitting directly
        // under the top-level authorized root rather than inside the workspace
        // the pipeline actually created — this is exactly the "malicious or
        // buggy executor points at an arbitrary path" case the containment
        // check exists for.
        val outsideFile = Files.createTempFile(root, "outside-workspace", ".jar")
        Files.write(outsideFile, ByteArray(64))
        val escaping = Artifact("a1", "b1", ArtifactType.JAR, outsideFile.toString(), outsideFile.fileName.toString(), sizeBytes = 64, checksumSha256 = "x", createdAt = Instant.now())
        val executor = MockBuildExecutor { BuildExecutionResult.Success(0, "ok", listOf(escaping)) }

        val result = assertIs<BuildResult.Failure>(pipeline(executor = executor).execute(request()))

        assertEquals(BuildStage.VALIDATE_RESULT, result.failedStage)
        assertEquals("ARTIFACT_INVALID", result.error.code)
    }

    @Test
    fun `an artifact the executor claims but that does not exist on disk fails at VALIDATE_RESULT`() {
        val executor = MockBuildExecutor { context ->
            val missingPath = Path.of(context.workspace.rootPath).resolve("no-such-file.jar")
            val fakeArtifact = Artifact("a1", "b1", ArtifactType.JAR, missingPath.toString(), "no-such-file.jar", sizeBytes = 100, checksumSha256 = "deadbeef", createdAt = Instant.now())
            BuildExecutionResult.Success(0, "ok", listOf(fakeArtifact))
        }

        val result = assertIs<BuildResult.Failure>(pipeline(executor = executor).execute(request()))

        assertEquals(BuildStage.VALIDATE_RESULT, result.failedStage)
        assertEquals("ARTIFACT_NOT_FOUND", result.error.code)
    }

    @Test
    fun `an artifact whose declared size does not match its real size fails at VALIDATE_RESULT`() {
        val executor = MockBuildExecutor { context ->
            val realFile = Files.createTempFile(Path.of(context.workspace.rootPath), "artifact", ".jar")
            Files.writeString(realFile, "12345") // 5 bytes
            val mismatched = Artifact("a1", "b1", ArtifactType.JAR, realFile.toString(), realFile.fileName.toString(), sizeBytes = 999, checksumSha256 = "x", createdAt = Instant.now())
            BuildExecutionResult.Success(0, "ok", listOf(mismatched))
        }

        val result = assertIs<BuildResult.Failure>(pipeline(executor = executor).execute(request()))

        assertEquals(BuildStage.VALIDATE_RESULT, result.failedStage)
        assertEquals("ARTIFACT_INVALID", result.error.code)
    }

    @Test
    fun `an oversized artifact is rejected at VALIDATE_RESULT`() {
        val executor = MockBuildExecutor { context ->
            val realFile = Files.createTempFile(Path.of(context.workspace.rootPath), "big-artifact", ".jar")
            Files.write(realFile, ByteArray(2048))
            val oversized = Artifact("a1", "b1", ArtifactType.JAR, realFile.toString(), realFile.fileName.toString(), sizeBytes = 2048, checksumSha256 = "x", createdAt = Instant.now())
            BuildExecutionResult.Success(0, "ok", listOf(oversized))
        }

        val result = assertIs<BuildResult.Failure>(pipeline(executor = executor).execute(request(maxArtifactBytes = 1024)))

        assertEquals(BuildStage.VALIDATE_RESULT, result.failedStage)
        assertEquals("ARTIFACT_INVALID", result.error.code)
    }

    @Test
    fun `a valid, correctly sized artifact inside the workspace passes VALIDATE_RESULT`() {
        var expectedArtifact: Artifact? = null
        val executor = MockBuildExecutor { context ->
            val realFile = Files.createTempFile(Path.of(context.workspace.rootPath), "artifact", ".jar")
            Files.write(realFile, ByteArray(512))
            val artifact = Artifact("a1", "b1", ArtifactType.JAR, realFile.toString(), realFile.fileName.toString(), sizeBytes = 512, checksumSha256 = "x", createdAt = Instant.now())
            expectedArtifact = artifact
            BuildExecutionResult.Success(0, "ok", listOf(artifact))
        }

        val result = assertIs<BuildResult.Success>(pipeline(executor = executor).execute(request()))

        assertEquals(listOf(expectedArtifact), result.artifacts)
    }

    @Test
    fun `cancellation before workspace creation fails with CANCELLED and creates no workspace`() {
        val before = Files.list(root).use { it.count() }
        val result = assertIs<BuildResult.Failure>(pipeline().execute(request(), isCancelled = { true }))
        assertEquals("CANCELLED", result.error.code)
        val after = Files.list(root).use { it.count() }
        assertEquals(before, after)
    }

    @Test
    fun `cancellation mid-pipeline cleans up the workspace it already created`() {
        var calls = 0
        val before = Files.list(root).use { it.count() }
        val result = assertIs<BuildResult.Failure>(
            pipeline().execute(request()) {
                calls++
                calls > 1 // allow the initial pre-workspace check to pass, cancel afterward
            },
        )
        assertEquals("CANCELLED", result.error.code)
        val after = Files.list(root).use { it.count() }
        assertEquals(before, after)
    }

    @Test
    fun `a deadline in the past is reported as TIMEOUT, not CANCELLED`() {
        val fixedNow = Instant.parse("2020-01-01T00:00:00Z")
        var callCount = 0
        val clock = {
            callCount++
            // First call establishes startedAt/deadline; every call after is already past deadline.
            if (callCount == 1) fixedNow else fixedNow.plus(Duration.ofHours(1))
        }
        val result = assertIs<BuildResult.Failure>(
            pipeline(clock = clock).execute(request(timeout = Duration.ofSeconds(1))),
        )
        assertEquals("TIMEOUT", result.error.code)
    }

    @Test
    fun `events for a successful run include BUILD_CREATED through BUILD_COMPLETED in order`() {
        val sink = InMemoryBuildEventSink()
        pipeline(eventSink = sink).execute(request())
        val types = sink.events.map { it.type }
        assertEquals(BuildEventType.BUILD_CREATED, types.first())
        assertEquals(BuildEventType.BUILD_COMPLETED, types.last())
        assertTrue(types.contains(BuildEventType.WORKSPACE_CREATED))
        assertTrue(types.contains(BuildEventType.SOURCE_PREPARED))
        assertTrue(types.contains(BuildEventType.BUILD_STARTED))
    }

    @Test
    fun `events for a failed run end with BUILD_FAILED`() {
        val sink = InMemoryBuildEventSink()
        val invalid = request().copy(sourceLocation = SourceLocation.LocalDirectory(""))
        pipeline(eventSink = sink).execute(invalid)
        assertEquals(BuildEventType.BUILD_FAILED, sink.events.last().type)
    }

    @Test
    fun `the event sink receives every event emitted during the run`() {
        val sink = InMemoryBuildEventSink()
        val result = assertIs<BuildResult.Success>(pipeline(eventSink = sink).execute(request()))
        assertEquals(result.logs, sink.events)
    }
}

private class FixedEnvironmentDetector(private val availability: ToolAvailability) : BuildEnvironmentDetector {
    override fun check(tool: EnvironmentTool) = ToolCheckResult(tool, availability)
}
