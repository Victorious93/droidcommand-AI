package ai.droidforge.apklifecycle

import ai.droidforge.build.Artifact
import ai.droidforge.build.ArtifactType
import ai.droidforge.build.BuildError
import ai.droidforge.build.BuildResult
import ai.droidforge.build.BuildStage
import ai.droidforge.build.BuildTarget
import ai.droidforge.build.ProjectType
import java.time.Instant

internal fun apkArtifact(fileName: String = "app-debug.apk") = Artifact(
    artifactId = "a1",
    buildId = "b1",
    type = ArtifactType.APK,
    path = "/workspace/$fileName",
    fileName = fileName,
    sizeBytes = 1024,
    checksumSha256 = "deadbeef",
    createdAt = Instant.now(),
)

internal fun buildSuccess(artifacts: List<Artifact> = listOf(apkArtifact())) = BuildResult.Success(
    buildId = "b1",
    workspaceId = "ws1",
    projectType = ProjectType.ANDROID,
    target = BuildTarget.DEBUG,
    durationMillis = 100,
    artifacts = artifacts,
    logs = emptyList(),
    warnings = emptyList(),
)

internal fun buildFailure() = BuildResult.Failure(
    buildId = "b1",
    failedStage = BuildStage.EXECUTE,
    error = BuildError.BuildFailed("simulated compiler failure"),
    logs = emptyList(),
)

internal class ScriptedApkLifecycleExecutor(
    private val installResult: InstallResult = InstallResult.Failure("not scripted"),
    private val launchResult: LaunchResult = LaunchResult.Failure("not scripted"),
    private val logsResult: LogsResult = LogsResult.Failure("not scripted"),
    private val testRunResult: TestRunResult = TestRunResult.Failure("not scripted"),
    private val uninstallResult: UninstallResult = UninstallResult.Failure("not scripted"),
) : ApkLifecycleExecutor {
    var installCalls = mutableListOf<InstallRequest>()
        private set
    var launchCalls = mutableListOf<String>()
        private set
    var collectLogsCalls = 0
        private set
    var runTestsCalls = 0
        private set

    override fun install(request: InstallRequest): InstallResult {
        installCalls += request
        return installResult
    }

    override fun uninstall(packageName: String): UninstallResult = uninstallResult

    override fun launch(packageName: String): LaunchResult {
        launchCalls += packageName
        return launchResult
    }

    override fun collectLogs(packageName: String, sinceMillis: Long?): LogsResult {
        collectLogsCalls++
        return logsResult
    }

    override fun runInstrumentedTests(packageName: String, testPackage: String?): TestRunResult {
        runTestsCalls++
        return testRunResult
    }
}
