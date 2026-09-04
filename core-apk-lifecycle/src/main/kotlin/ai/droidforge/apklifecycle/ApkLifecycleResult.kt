package ai.droidforge.apklifecycle

sealed class ApkLifecycleResult {
    data class Success(
        val installResult: InstallResult.Success,
        val launchResult: LaunchResult.Success,
        val logs: List<LogEntry>,
        val testResults: TestRunResult.Success?,
        val warnings: List<String>,
        val durationMillis: Long,
        val events: List<ApkLifecycleEvent>,
    ) : ApkLifecycleResult()

    data class Failure(
        val stage: ApkLifecycleStage,
        val error: ApkLifecycleError,
        val events: List<ApkLifecycleEvent>,
    ) : ApkLifecycleResult()
}
