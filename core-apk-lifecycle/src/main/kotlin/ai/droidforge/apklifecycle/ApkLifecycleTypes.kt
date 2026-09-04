package ai.droidforge.apklifecycle

import java.time.Instant

data class InstallRequest(
    val artifactPath: String,
    val packageName: String,
    val replaceExisting: Boolean = true,
    val grantRuntimePermissions: Boolean = false,
)

sealed class InstallResult {
    data class Success(val packageName: String, val versionCode: Long? = null) : InstallResult()
    data class Failure(val reason: String, val cause: Throwable? = null) : InstallResult()
}

sealed class UninstallResult {
    data class Success(val packageName: String) : UninstallResult()
    data class Failure(val reason: String, val cause: Throwable? = null) : UninstallResult()
}

sealed class LaunchResult {
    data class Success(val message: String) : LaunchResult()
    data class Failure(val reason: String, val cause: Throwable? = null) : LaunchResult()
}

enum class LogLevel { VERBOSE, DEBUG, INFO, WARN, ERROR, FATAL }

data class LogEntry(val timestamp: Instant, val level: LogLevel, val tag: String, val message: String)

sealed class LogsResult {
    data class Success(val entries: List<LogEntry>) : LogsResult()
    data class Failure(val reason: String) : LogsResult()
}

enum class TestOutcome { PASSED, FAILED, SKIPPED, ERROR }

data class TestCaseResult(
    val className: String,
    val methodName: String,
    val outcome: TestOutcome,
    val durationMillis: Long,
    val failureMessage: String? = null,
)

sealed class TestRunResult {
    data class Success(val results: List<TestCaseResult>, val totalDurationMillis: Long) : TestRunResult()
    data class Failure(val reason: String, val cause: Throwable? = null) : TestRunResult()
}
