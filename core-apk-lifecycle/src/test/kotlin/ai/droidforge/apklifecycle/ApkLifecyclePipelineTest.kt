package ai.droidforge.apklifecycle

import ai.droidforge.build.ArtifactType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ApkLifecyclePipelineTest {
    @Test
    fun `a failed build short-circuits at BUILD without touching the executor`() {
        val executor = ScriptedApkLifecycleExecutor()
        val pipeline = ApkLifecyclePipeline(executor)

        val result = assertIs<ApkLifecycleResult.Failure>(pipeline.run(buildFailure(), "com.example.app"))

        assertEquals(ApkLifecycleStage.BUILD, result.stage)
        assertEquals("BUILD_FAILED", result.error.code)
        assertEquals(0, executor.installCalls.size)
    }

    @Test
    fun `a build with no APK artifact fails at SELECT_ARTIFACT`() {
        val executor = ScriptedApkLifecycleExecutor()
        val pipeline = ApkLifecyclePipeline(executor)
        val buildWithNoApk = buildSuccess(artifacts = emptyList())

        val result = assertIs<ApkLifecycleResult.Failure>(pipeline.run(buildWithNoApk, "com.example.app"))

        assertEquals(ApkLifecycleStage.SELECT_ARTIFACT, result.stage)
        assertEquals("NO_ARTIFACT_FOUND", result.error.code)
        assertEquals(0, executor.installCalls.size)
    }

    @Test
    fun `a build with only non-APK artifacts also fails at SELECT_ARTIFACT`() {
        val executor = ScriptedApkLifecycleExecutor()
        val pipeline = ApkLifecyclePipeline(executor)
        val jarOnly = buildSuccess(artifacts = listOf(apkArtifact("lib.jar").copy(type = ArtifactType.JAR)))

        val result = assertIs<ApkLifecycleResult.Failure>(pipeline.run(jarOnly, "com.example.app"))

        assertEquals(ApkLifecycleStage.SELECT_ARTIFACT, result.stage)
    }

    @Test
    fun `install failure stops the pipeline before launch`() {
        val executor = ScriptedApkLifecycleExecutor(installResult = InstallResult.Failure("insufficient storage"))
        val pipeline = ApkLifecyclePipeline(executor)

        val result = assertIs<ApkLifecycleResult.Failure>(pipeline.run(buildSuccess(), "com.example.app"))

        assertEquals(ApkLifecycleStage.INSTALL, result.stage)
        assertEquals("INSTALL_FAILED", result.error.code)
        assertEquals(0, executor.launchCalls.size)
    }

    @Test
    fun `install passes the selected artifact's path and the requested package name`() {
        val executor = ScriptedApkLifecycleExecutor(
            installResult = InstallResult.Success("com.example.app"),
            launchResult = LaunchResult.Failure("stop here"),
        )
        val pipeline = ApkLifecyclePipeline(executor)

        pipeline.run(buildSuccess(), "com.example.app")

        val request = executor.installCalls.single()
        assertEquals("/workspace/app-debug.apk", request.artifactPath)
        assertEquals("com.example.app", request.packageName)
    }

    @Test
    fun `launch failure stops the pipeline before log collection`() {
        val executor = ScriptedApkLifecycleExecutor(
            installResult = InstallResult.Success("com.example.app"),
            launchResult = LaunchResult.Failure("activity not found"),
        )
        val pipeline = ApkLifecyclePipeline(executor)

        val result = assertIs<ApkLifecycleResult.Failure>(pipeline.run(buildSuccess(), "com.example.app"))

        assertEquals(ApkLifecycleStage.LAUNCH, result.stage)
        assertEquals(0, executor.collectLogsCalls)
    }

    @Test
    fun `a log collection failure is a warning, not a pipeline failure`() {
        val executor = ScriptedApkLifecycleExecutor(
            installResult = InstallResult.Success("com.example.app"),
            launchResult = LaunchResult.Success("launched"),
            logsResult = LogsResult.Failure("logcat unavailable"),
        )
        val pipeline = ApkLifecyclePipeline(executor)

        val result = assertIs<ApkLifecycleResult.Success>(pipeline.run(buildSuccess(), "com.example.app"))

        assertEquals(emptyList(), result.logs)
        assertTrue(result.warnings.any { it.contains("logcat unavailable") })
    }

    @Test
    fun `a full success without tests returns Success with empty testResults`() {
        val executor = ScriptedApkLifecycleExecutor(
            installResult = InstallResult.Success("com.example.app"),
            launchResult = LaunchResult.Success("launched"),
            logsResult = LogsResult.Success(emptyList()),
        )
        val pipeline = ApkLifecyclePipeline(executor)

        val result = assertIs<ApkLifecycleResult.Success>(pipeline.run(buildSuccess(), "com.example.app", runTests = false))

        assertEquals(null, result.testResults)
        assertEquals(0, executor.runTestsCalls)
    }

    @Test
    fun `runTests = false never calls runInstrumentedTests`() {
        val executor = ScriptedApkLifecycleExecutor(
            installResult = InstallResult.Success("com.example.app"),
            launchResult = LaunchResult.Success("launched"),
            logsResult = LogsResult.Success(emptyList()),
        )
        ApkLifecyclePipeline(executor).run(buildSuccess(), "com.example.app", runTests = false)
        assertEquals(0, executor.runTestsCalls)
    }

    @Test
    fun `a failed test run harness (not a failed test) fails the pipeline at TEST`() {
        val executor = ScriptedApkLifecycleExecutor(
            installResult = InstallResult.Success("com.example.app"),
            launchResult = LaunchResult.Success("launched"),
            logsResult = LogsResult.Success(emptyList()),
            testRunResult = TestRunResult.Failure("instrumentation crashed"),
        )
        val pipeline = ApkLifecyclePipeline(executor)

        val result = assertIs<ApkLifecycleResult.Failure>(pipeline.run(buildSuccess(), "com.example.app", runTests = true))

        assertEquals(ApkLifecycleStage.TEST, result.stage)
        assertEquals("TEST_RUN_FAILED", result.error.code)
    }

    @Test
    fun `a completed test run with failing test cases is still a pipeline Success`() {
        val failingTest = TestCaseResult("com.example.FooTest", "testBar", TestOutcome.FAILED, 10, "assertion failed")
        val executor = ScriptedApkLifecycleExecutor(
            installResult = InstallResult.Success("com.example.app"),
            launchResult = LaunchResult.Success("launched"),
            logsResult = LogsResult.Success(emptyList()),
            testRunResult = TestRunResult.Success(listOf(failingTest), totalDurationMillis = 10),
        )
        val pipeline = ApkLifecyclePipeline(executor)

        val result = assertIs<ApkLifecycleResult.Success>(pipeline.run(buildSuccess(), "com.example.app", runTests = true))

        assertEquals(1, result.testResults?.results?.size)
        assertEquals(TestOutcome.FAILED, result.testResults?.results?.single()?.outcome)
    }

    @Test
    fun `events cover the full run from LIFECYCLE_STARTED to LIFECYCLE_COMPLETED`() {
        val sink = mutableListOf<ApkLifecycleEvent>()
        val executor = ScriptedApkLifecycleExecutor(
            installResult = InstallResult.Success("com.example.app"),
            launchResult = LaunchResult.Success("launched"),
            logsResult = LogsResult.Success(emptyList()),
        )
        val pipeline = ApkLifecyclePipeline(executor, eventSink = { sink += it })

        pipeline.run(buildSuccess(), "com.example.app")

        assertEquals(ApkLifecycleEventType.LIFECYCLE_STARTED, sink.first().type)
        assertEquals(ApkLifecycleEventType.LIFECYCLE_COMPLETED, sink.last().type)
        assertTrue(sink.any { it.type == ApkLifecycleEventType.INSTALL_COMPLETED })
        assertTrue(sink.any { it.type == ApkLifecycleEventType.LAUNCH_COMPLETED })
    }

    @Test
    fun `events for a failed run end with LIFECYCLE_FAILED`() {
        val sink = mutableListOf<ApkLifecycleEvent>()
        val pipeline = ApkLifecyclePipeline(ScriptedApkLifecycleExecutor(), eventSink = { sink += it })

        pipeline.run(buildFailure(), "com.example.app")

        assertEquals(ApkLifecycleEventType.LIFECYCLE_FAILED, sink.last().type)
    }
}
