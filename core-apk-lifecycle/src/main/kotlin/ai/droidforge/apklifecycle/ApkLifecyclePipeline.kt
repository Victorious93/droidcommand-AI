package ai.droidforge.apklifecycle

import ai.droidforge.build.ArtifactType
import ai.droidforge.build.BuildResult
import java.time.Instant

/**
 * Orchestrates Build -> select artifact -> install -> launch -> collect
 * logs -> (optionally) test -> result. Consumes an already-completed
 * `core-build.BuildResult` rather than running a build itself — building
 * and deploying are separate concerns with separate authorization
 * requirements, so this pipeline starts from where `core-build.BuildPipeline`
 * leaves off. Every stage has explicit success/failure semantics via
 * [ApkLifecycleStage] and [ApkLifecycleError], the same discipline
 * `core-build.BuildPipeline` uses.
 *
 * Log collection is deliberately best-effort: a failure to read logs is a
 * warning, not a pipeline failure — the app installed and launched, which
 * is the outcome that actually matters; logs are diagnostic. A failed
 * *test run* (the harness itself couldn't execute, as opposed to a test
 * that ran and failed) is a pipeline failure, since it means the intended
 * work didn't happen at all.
 */
class ApkLifecyclePipeline(
    private val executor: ApkLifecycleExecutor,
    private val eventSink: ApkLifecycleEventSink = ApkLifecycleEventSink.NOOP,
    private val clock: () -> Instant = Instant::now,
) {
    fun run(
        build: BuildResult,
        packageName: String,
        runTests: Boolean = false,
        testPackage: String? = null,
    ): ApkLifecycleResult {
        val startedAt = clock()
        val events = mutableListOf<ApkLifecycleEvent>()
        fun emit(type: ApkLifecycleEventType, message: String, metadata: Map<String, String> = emptyMap()) {
            val event = ApkLifecycleEvent(type, clock(), message, metadata)
            events += event
            eventSink.emit(event)
        }

        fun fail(stage: ApkLifecycleStage, error: ApkLifecycleError): ApkLifecycleResult.Failure {
            emit(ApkLifecycleEventType.LIFECYCLE_FAILED, "Failed at $stage: ${error.message}", mapOf("code" to error.code))
            return ApkLifecycleResult.Failure(stage, error, events)
        }

        emit(ApkLifecycleEventType.LIFECYCLE_STARTED, "Starting APK lifecycle for '$packageName'")

        // STAGE: BUILD
        if (build is BuildResult.Failure) {
            return fail(ApkLifecycleStage.BUILD, ApkLifecycleError.BuildFailed(build.error))
        }
        val success = build as BuildResult.Success

        // STAGE: SELECT_ARTIFACT
        val artifact = success.artifacts.firstOrNull { it.type == ArtifactType.APK }
            ?: return fail(ApkLifecycleStage.SELECT_ARTIFACT, ApkLifecycleError.NoArtifactFound("Build ${success.buildId} produced no APK artifact"))
        emit(ApkLifecycleEventType.ARTIFACT_SELECTED, "Selected artifact ${artifact.fileName}", mapOf("artifactId" to artifact.artifactId))

        // STAGE: INSTALL
        emit(ApkLifecycleEventType.INSTALL_STARTED, "Installing ${artifact.fileName} as $packageName")
        val installResult = executor.install(InstallRequest(artifact.path, packageName))
        val installedSuccess = when (installResult) {
            is InstallResult.Failure -> {
                emit(ApkLifecycleEventType.INSTALL_FAILED, installResult.reason)
                return fail(ApkLifecycleStage.INSTALL, ApkLifecycleError.InstallFailed(installResult.reason))
            }
            is InstallResult.Success -> installResult
        }
        emit(ApkLifecycleEventType.INSTALL_COMPLETED, "Installed $packageName")

        // STAGE: LAUNCH
        emit(ApkLifecycleEventType.LAUNCH_STARTED, "Launching $packageName")
        val launchResult = executor.launch(packageName)
        val launchedSuccess = when (launchResult) {
            is LaunchResult.Failure -> {
                emit(ApkLifecycleEventType.LAUNCH_FAILED, launchResult.reason)
                return fail(ApkLifecycleStage.LAUNCH, ApkLifecycleError.LaunchFailed(launchResult.reason))
            }
            is LaunchResult.Success -> launchResult
        }
        emit(ApkLifecycleEventType.LAUNCH_COMPLETED, launchedSuccess.message)

        // STAGE: (best-effort) COLLECT_LOGS
        val warnings = mutableListOf<String>()
        val logs = when (val logsResult = executor.collectLogs(packageName)) {
            is LogsResult.Success -> {
                emit(ApkLifecycleEventType.LOGS_COLLECTED, "Collected ${logsResult.entries.size} log entries")
                logsResult.entries
            }
            is LogsResult.Failure -> {
                emit(ApkLifecycleEventType.LOGS_COLLECTION_FAILED, logsResult.reason)
                warnings += "Log collection failed: ${logsResult.reason}"
                emptyList()
            }
        }

        // STAGE: TEST (optional)
        var testResults: TestRunResult.Success? = null
        if (runTests) {
            emit(ApkLifecycleEventType.TEST_RUN_STARTED, "Running instrumented tests for $packageName")
            when (val testRunResult = executor.runInstrumentedTests(packageName, testPackage)) {
                is TestRunResult.Failure -> {
                    emit(ApkLifecycleEventType.TEST_RUN_FAILED, testRunResult.reason)
                    return fail(ApkLifecycleStage.TEST, ApkLifecycleError.TestRunFailed(testRunResult.reason))
                }
                is TestRunResult.Success -> {
                    testResults = testRunResult
                    emit(ApkLifecycleEventType.TEST_RUN_COMPLETED, "${testRunResult.results.size} test(s) ran")
                }
            }
        }

        emit(ApkLifecycleEventType.LIFECYCLE_COMPLETED, "APK lifecycle completed for $packageName")
        return ApkLifecycleResult.Success(
            installResult = installedSuccess,
            launchResult = launchedSuccess,
            logs = logs,
            testResults = testResults,
            warnings = warnings,
            durationMillis = java.time.Duration.between(startedAt, clock()).toMillis(),
            events = events,
        )
    }
}
