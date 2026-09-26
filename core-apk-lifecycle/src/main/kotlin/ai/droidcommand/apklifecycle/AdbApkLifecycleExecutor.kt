package ai.droidcommand.apklifecycle

import ai.droidcommand.shell.ShellCommand
import ai.droidcommand.shell.ShellExecutionResult
import ai.droidcommand.shell.ShellExecutor
import java.time.LocalDateTime
import java.time.Year
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A real, `adb`-backed [ApkLifecycleExecutor] — the same PC-drives-a-tethered-phone topology
 * `core-root.AdbRootExecutor`/`core-tools-android.AdbDeviceController` use, applied here to install,
 * launch, log-collection, and instrumented-test execution (ROADMAP-087). [shell] is an already-configured
 * [ShellExecutor] (e.g. `ProcessBuilderShellExecutor(ShellSecurityPolicy(allowedExecutables = setOf("adb")))`)
 * — this class has no opinion on the security policy gating it, matching every other `Adb*` class in this
 * repository.
 *
 * Every method here is genuinely destructive/mutating by nature (install, uninstall, launch, run tests) —
 * unlike [ai.droidcommand.tools.android.AdbDeviceController]'s deliberate read-only-methods-only scope,
 * there is no read-only subset of "deploy and run an app" to cut down to. That authorization boundary is
 * this class's caller's responsibility (`ApkLifecycleTool` is already `SecurityLevel.SENSITIVE`), not
 * this class's.
 *
 * Named, honest limitations:
 * - [collectLogs] parses `adb logcat -v threadtime` timestamps by assuming the current calendar year and
 *   the host machine's local time zone, since `threadtime` output carries neither — a device whose clock
 *   is in a different year or time zone than the host running `adb` would have its log timestamps
 *   misattributed. A future revision could switch to `-v epoch`, not used here because this environment
 *   has no real device to verify its exact column layout against.
 * - [runInstrumentedTests] parses the well-documented `am instrument -w -r` raw status-code protocol
 *   (`INSTRUMENTATION_STATUS`/`INSTRUMENTATION_STATUS_CODE` key/value blocks) well enough to recover
 *   each test's class, method, outcome, and failure stack — but that protocol carries no structured
 *   per-test duration field, so every [TestCaseResult.durationMillis] is `0`;
 *   [TestRunResult.Success.totalDurationMillis] is real (measured wall-clock time around the whole
 *   `am instrument` invocation), not fabricated.
 */
class AdbApkLifecycleExecutor(
    private val shell: ShellExecutor,
    private val adbExecutable: String = "adb",
    private val serial: String? = null,
    private val instrumentTimeoutMillis: Long = 120_000,
) : ApkLifecycleExecutor {
    private fun adbArgv(vararg args: String): List<String> =
        (if (serial != null) listOf(adbExecutable, "-s", serial) else listOf(adbExecutable)) + args

    private fun runAdb(vararg args: String, timeoutMillis: Long = 30_000): ShellExecutionResult {
        val argv = adbArgv(*args)
        return shell.execute(ShellCommand(executable = argv.first(), args = argv.drop(1), timeoutMillis = timeoutMillis))
    }

    private fun successfulOutput(vararg args: String): String? {
        val result = runAdb(*args)
        return if (result is ShellExecutionResult.Success && result.exitCode == 0) result.stdout else null
    }

    /** True if any line of [output], trimmed, is exactly `Success` (case-insensitive) — real `adb install`/`uninstall` output can carry extra lines before it (e.g. "Performing Streamed Install"). */
    private fun hasSuccessLine(output: String): Boolean =
        output.lineSequence().any { it.trim().equals("Success", ignoreCase = true) }

    override fun install(request: InstallRequest): InstallResult {
        val args = buildList {
            add("install")
            if (request.replaceExisting) add("-r")
            if (request.grantRuntimePermissions) add("-g")
            add(request.artifactPath)
        }
        return when (val result = runAdb(*args.toTypedArray())) {
            is ShellExecutionResult.Failure ->
                InstallResult.Failure("Cannot install '${request.packageName}': ${result.reason}", result.cause)
            is ShellExecutionResult.Success -> {
                val output = result.stdout + result.stderr
                if (result.exitCode == 0 && hasSuccessLine(output)) {
                    InstallResult.Success(request.packageName, versionCode = readVersionCode(request.packageName))
                } else {
                    InstallResult.Failure(
                        "Cannot install '${request.packageName}': 'adb install' reported: ${output.trim().ifBlank { "exit code ${result.exitCode}" }}",
                    )
                }
            }
        }
    }

    private fun readVersionCode(packageName: String): Long? {
        val output = successfulOutput("shell", "dumpsys", "package", packageName) ?: return null
        return VERSION_CODE_PATTERN.find(output)?.groupValues?.get(1)?.toLongOrNull()
    }

    override fun uninstall(packageName: String): UninstallResult {
        return when (val result = runAdb("uninstall", packageName)) {
            is ShellExecutionResult.Failure ->
                UninstallResult.Failure("Cannot uninstall '$packageName': ${result.reason}", result.cause)
            is ShellExecutionResult.Success -> {
                val output = result.stdout + result.stderr
                if (result.exitCode == 0 && hasSuccessLine(output)) {
                    UninstallResult.Success(packageName)
                } else {
                    UninstallResult.Failure(
                        "Cannot uninstall '$packageName': 'adb uninstall' reported: ${output.trim().ifBlank { "exit code ${result.exitCode}" }}",
                    )
                }
            }
        }
    }

    /**
     * Resolves the package's actual launcher activity via `adb shell cmd package resolve-activity --brief`
     * (rather than guessing a `.MainActivity` convention or requiring the caller to supply a component)
     * and starts it via `adb shell am start -n`.
     */
    override fun launch(packageName: String): LaunchResult {
        val resolveOutput = successfulOutput("shell", "cmd", "package", "resolve-activity", "--brief", packageName)
            ?: return LaunchResult.Failure("Cannot launch '$packageName': 'adb shell cmd package resolve-activity' failed")
        val component = resolveOutput.lineSequence()
            .map { it.trim() }
            .lastOrNull { it.contains("/") && !it.contains(" ") }
            ?: return LaunchResult.Failure(
                "Cannot launch '$packageName': no launchable activity resolved (is it installed, and does it declare a LAUNCHER activity?)",
            )
        return when (val startResult = runAdb("shell", "am", "start", "-n", component)) {
            is ShellExecutionResult.Failure ->
                LaunchResult.Failure("Cannot launch '$packageName': ${startResult.reason}", startResult.cause)
            is ShellExecutionResult.Success -> {
                val output = startResult.stdout + startResult.stderr
                if (startResult.exitCode == 0 && !output.contains("Error", ignoreCase = true) && !output.contains("Exception")) {
                    LaunchResult.Success("Started $component")
                } else {
                    LaunchResult.Failure(
                        "Cannot launch '$packageName': 'adb shell am start' reported: ${output.trim().ifBlank { "exit code ${startResult.exitCode}" }}",
                    )
                }
            }
        }
    }

    /**
     * Finds the package's running pid via `adb shell pidof` and dumps only that pid's backlog via
     * `adb logcat -d --pid=<pid>` (dump-and-exit, not a live follow) — never the device's entire log.
     * [sinceMillis] filtering happens client-side, after parsing, rather than via `logcat -T`, to avoid
     * depending on an epoch-time filter format this environment has no real device to verify.
     */
    override fun collectLogs(packageName: String, sinceMillis: Long?): LogsResult {
        val pid = successfulOutput("shell", "pidof", packageName)?.trim()?.split(Regex("\\s+"))?.firstOrNull { it.isNotBlank() }
            ?: return LogsResult.Failure("Cannot collect logs for '$packageName': no running process found (adb shell pidof returned nothing — is it running?)")
        return when (val result = runAdb("logcat", "-d", "-v", "threadtime", "--pid=$pid")) {
            is ShellExecutionResult.Failure -> LogsResult.Failure("Cannot collect logs for '$packageName': ${result.reason}")
            is ShellExecutionResult.Success -> {
                if (result.exitCode != 0) {
                    LogsResult.Failure("Cannot collect logs for '$packageName': 'adb logcat' exited ${result.exitCode}: ${result.stderr}")
                } else {
                    val entries = parseLogcatThreadTime(result.stdout)
                    LogsResult.Success(if (sinceMillis != null) entries.filter { it.timestamp.toEpochMilli() >= sinceMillis } else entries)
                }
            }
        }
    }

    private fun parseLogcatThreadTime(output: String): List<LogEntry> {
        val currentYear = Year.now().value
        return output.lineSequence().mapNotNull { line ->
            val match = LOGCAT_THREADTIME_PATTERN.find(line) ?: return@mapNotNull null
            val (dateTime, levelChar, tag, message) = match.destructured
            val level = levelFromChar(levelChar.firstOrNull()) ?: return@mapNotNull null
            val instant = try {
                LocalDateTime.parse("$currentYear-$dateTime", LOGCAT_DATE_TIME_FORMATTER)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
            } catch (e: Exception) {
                return@mapNotNull null
            }
            LogEntry(timestamp = instant, level = level, tag = tag.trim(), message = message)
        }.toList()
    }

    private fun levelFromChar(c: Char?): LogLevel? = when (c) {
        'V' -> LogLevel.VERBOSE
        'D' -> LogLevel.DEBUG
        'I' -> LogLevel.INFO
        'W' -> LogLevel.WARN
        'E' -> LogLevel.ERROR
        'F' -> LogLevel.FATAL
        else -> null
    }

    /**
     * Discovers the real instrumentation runner via `adb shell pm list instrumentation` rather than
     * guessing a conventional runner class name (e.g. `androidx.test.runner.AndroidJUnitRunner`) —
     * matching [testPackage] against the instrumentation's own test-APK package when given, or the
     * app's package as the instrumentation's declared target otherwise.
     */
    override fun runInstrumentedTests(packageName: String, testPackage: String?): TestRunResult {
        val listOutput = successfulOutput("shell", "pm", "list", "instrumentation")
            ?: return TestRunResult.Failure("Cannot run instrumented tests for '$packageName': 'adb shell pm list instrumentation' failed")
        val candidates = listOutput.lineSequence()
            .mapNotNull { INSTRUMENTATION_LINE_PATTERN.find(it.trim()) }
            .map { InstrumentationCandidate(testPackage = it.groupValues[1], runnerClass = it.groupValues[2], targetPackage = it.groupValues[3]) }
            .toList()
        val chosen = candidates.firstOrNull { candidate ->
            if (testPackage != null) candidate.testPackage == testPackage else candidate.targetPackage == packageName
        } ?: return TestRunResult.Failure(
            "Cannot run instrumented tests for '$packageName': no instrumentation registered " +
                (testPackage?.let { "for test package '$it'" } ?: "targeting '$packageName'") +
                " (checked 'adb shell pm list instrumentation')",
        )

        val startedAtNanos = System.nanoTime()
        val instrumentResult = runAdb(
            "shell", "am", "instrument", "-w", "-r", "${chosen.testPackage}/${chosen.runnerClass}",
            timeoutMillis = instrumentTimeoutMillis,
        )
        val totalDurationMillis = (System.nanoTime() - startedAtNanos) / 1_000_000

        return when (instrumentResult) {
            is ShellExecutionResult.Failure ->
                TestRunResult.Failure("Cannot run instrumented tests for '$packageName': ${instrumentResult.reason}", instrumentResult.cause)
            is ShellExecutionResult.Success -> {
                val results = parseInstrumentationOutput(instrumentResult.stdout)
                if (results.isEmpty() && instrumentResult.exitCode != 0) {
                    TestRunResult.Failure(
                        "Cannot run instrumented tests for '$packageName': 'adb shell am instrument' exited ${instrumentResult.exitCode} " +
                            "with no parseable test results: ${instrumentResult.stdout.take(500)}",
                    )
                } else {
                    TestRunResult.Success(results, totalDurationMillis)
                }
            }
        }
    }

    private data class InstrumentationCandidate(val testPackage: String, val runnerClass: String, val targetPackage: String)

    /**
     * Parses `am instrument -w -r`'s raw `INSTRUMENTATION_STATUS`/`INSTRUMENTATION_STATUS_CODE`
     * key/value-block protocol. A status code of `1` (test started) or `2` (in progress) keeps
     * accumulating fields for the current test; a terminal code (`0` pass, `-1`/`-2`/`-4` error/failure/
     * assumption-failure, `-3` ignored) closes it out into a [TestCaseResult] and resets. A line that
     * isn't a recognized `INSTRUMENTATION_STATUS(_CODE)?:` prefix is treated as a continuation of the
     * most recently seen key's value — real instrumentation output does this for multi-line `stream`/
     * `stack` values (e.g. a stack trace). Lines belonging to the trailing `INSTRUMENTATION_RESULT`/
     * `INSTRUMENTATION_CODE` summary (after all per-test blocks) are recognized and excluded from that
     * continuation handling so they don't get misattributed to the last test's fields.
     */
    private fun parseInstrumentationOutput(rawOutput: String): List<TestCaseResult> {
        val results = mutableListOf<TestCaseResult>()
        var values = mutableMapOf<String, StringBuilder>()
        var lastKey: String? = null

        fun emit(code: Int) {
            val className = values["class"]?.toString()?.trim()
            val testName = values["test"]?.toString()?.trim()
            val outcome = when (code) {
                0 -> TestOutcome.PASSED
                -3 -> TestOutcome.SKIPPED
                -1, -2, -4 -> TestOutcome.FAILED
                else -> null
            }
            if (className != null && testName != null && outcome != null) {
                results += TestCaseResult(
                    className = className,
                    methodName = testName,
                    outcome = outcome,
                    durationMillis = 0,
                    failureMessage = values["stack"]?.toString()?.trim()?.ifBlank { null },
                )
            }
            values = mutableMapOf()
            lastKey = null
        }

        for (line in rawOutput.lineSequence()) {
            val codeMatch = INSTRUMENTATION_STATUS_CODE_LINE.find(line)
            val statusMatch = INSTRUMENTATION_STATUS_LINE.find(line)
            when {
                codeMatch != null -> {
                    val code = codeMatch.groupValues[1].toIntOrNull()
                    if (code != null && code != 1 && code != 2) emit(code)
                }
                statusMatch != null -> {
                    // A fresh "key=value" line always replaces, never appends: the real protocol re-sends
                    // the same key (e.g. "class", "test") with the same value across a test's start/end
                    // sub-blocks — appending here would duplicate it. Only bare continuation lines below
                    // (a multi-line "stack"/"stream" value with no repeated prefix) accumulate.
                    val key = statusMatch.groupValues[1]
                    values[key] = StringBuilder(statusMatch.groupValues[2])
                    lastKey = key
                }
                line.startsWith("INSTRUMENTATION_RESULT:") || line.startsWith("INSTRUMENTATION_CODE:") -> {
                    lastKey = null
                }
                else -> {
                    lastKey?.let { key -> values.getOrPut(key) { StringBuilder() }.append('\n').append(line) }
                }
            }
        }
        return results
    }

    private companion object {
        val VERSION_CODE_PATTERN = Regex("""versionCode=(\d+)""")
        val LOGCAT_THREADTIME_PATTERN = Regex(
            """^(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\s+\d+\s+\d+\s+([VDIWEF])\s+([^:]*):\s?(.*)$""",
        )
        val LOGCAT_DATE_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        val INSTRUMENTATION_LINE_PATTERN = Regex("""^instrumentation:([^/]+)/(\S+)\s+\(target=([^)]+)\)$""")
        val INSTRUMENTATION_STATUS_LINE = Regex("""^INSTRUMENTATION_STATUS:\s*([A-Za-z0-9_]+)=(.*)$""")
        val INSTRUMENTATION_STATUS_CODE_LINE = Regex("""^INSTRUMENTATION_STATUS_CODE:\s*(-?\d+)""")
    }
}
