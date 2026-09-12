package ai.droidcommand.root

import ai.droidcommand.agent.Logger
import ai.droidcommand.agent.NoOpLogger

private sealed class AdbRootShellProbe {
    data class Available(val uid: String) : AdbRootShellProbe()
    data class Unavailable(val reason: String) : AdbRootShellProbe()
}

/**
 * A real, verifying [RootProvider] that reaches root over `adb shell su -c '<command>'` rather than
 * spawning `su` as a local process the way [MagiskProvider] does. [MagiskProvider] assumes the JVM
 * itself runs *on* the rooted device (e.g. via Termux) — this class is for the other real topology:
 * the JVM runs on a PC, controlling a phone connected over USB debugging. Same two-tier honesty split
 * [MagiskProvider] establishes: [isDeviceConnected] ("is an authorized adb device present at all") is
 * cheap and passive, never touching the remote shell; [RootExecutor.isRootAvailable]/[isAuthorized]
 * ("has root actually been granted") is the only thing that spawns `su` remotely, cached for
 * [cacheTtlMillis] like [MagiskProvider]'s own probe.
 *
 * **Deliberate improvement over [MagiskProvider]'s own honest limitation:** [RootCommand.workingDirectory]/
 * [RootCommand.environment] are encoded directly into the remote shell command line (`cd <dir> &&
 * VAR=value ... command`) rather than applied to the local `adb` process's own working
 * directory/environment — the latter would silently do nothing remotely, since `adb` does not forward
 * its own local environment to the device shell. Every piece is [shellQuote]d individually before
 * being joined, and the resulting compound string is [shellQuote]d **again** before being appended to
 * the local `adb` argv: `adb shell` flattens all of its trailing local arguments into one space-joined
 * line before sending it to the device's shell (unlike a true local `ProcessBuilder` call, which
 * preserves argv boundaries) — without this second quoting layer a multi-word command would be
 * mis-split by the remote shell into unrelated words.
 *
 * [adbExecutable]/[serial]/[suExecutable]/[processTimeoutMillis]/[cacheTtlMillis] are all
 * constructor-injectable so tests can point them at real, controlled fixtures (scripted `adb`/`su`
 * shell scripts) rather than mocking process execution, mirroring [MagiskProvider]'s own precedent.
 * [serial] selects a specific device via `adb -s <serial>`; `null` targets whichever single device is
 * attached (matching plain `adb`'s own default behavior, including its honest failure when zero or
 * more than one device is present).
 */
class AdbRootExecutor(
    private val adbExecutable: String = "adb",
    private val serial: String? = null,
    private val suExecutable: String = "su",
    private val processTimeoutMillis: Long = 10_000,
    private val cacheTtlMillis: Long = 60_000,
    private val logger: Logger = NoOpLogger,
) : RootProvider {
    @Volatile
    private var cachedProbe: Pair<Long, AdbRootShellProbe>? = null

    override val info: RootProviderInfo
        get() = RootProviderInfo(providerId = "adb", providerName = "ADB (adb shell su)", version = getAndroidVersion())

    /** Presence only — an authorized device being connected is not proof root is functional. See class doc. */
    fun isDeviceConnected(): Boolean {
        val result = runProcess(adbArgv() + listOf("get-state"), processTimeoutMillis)
        return result is ProcessRunResult.Ran && result.exitCode == 0 && result.stdout.trim() == "device"
    }

    fun getAndroidVersion(): String? {
        val result = runProcess(adbArgv() + listOf("shell", "getprop", "ro.build.version.release"), processTimeoutMillis)
        if (result !is ProcessRunResult.Ran || result.exitCode != 0) return null
        return result.stdout.trim().ifBlank { null }
    }

    /** Forces the next root-shell check to re-probe rather than reuse a cached result. */
    fun invalidateCache() {
        cachedProbe = null
    }

    override fun isRootAvailable(): Boolean = probeRootShellCached() is AdbRootShellProbe.Available

    override fun isAuthorized(): Boolean = isRootAvailable()

    override fun getPrivilegeLevel(): PrivilegeLevel = when (probeRootShellCached()) {
        is AdbRootShellProbe.Available -> PrivilegeLevel.ROOT
        is AdbRootShellProbe.Unavailable -> if (isDeviceConnected()) PrivilegeLevel.USER else PrivilegeLevel.NONE
    }

    override fun checkHealth(probeShell: Boolean): RootHealth {
        if (!isDeviceConnected()) {
            return RootHealth(
                state = RootProviderState.UNAVAILABLE,
                rootAvailable = false,
                rootAuthorized = false,
                rootShellAvailable = false,
                privilegeLevel = PrivilegeLevel.NONE,
                lastError = "No authorized adb device connected",
            )
        }
        val probe = if (probeShell) probeRootShellCached() else cachedProbe?.second
        return when (probe) {
            is AdbRootShellProbe.Available -> RootHealth(
                state = RootProviderState.AVAILABLE,
                rootAvailable = true,
                rootAuthorized = true,
                rootShellAvailable = true,
                privilegeLevel = PrivilegeLevel.ROOT,
                lastError = null,
            )
            is AdbRootShellProbe.Unavailable -> RootHealth(
                state = RootProviderState.REQUIRES_PERMISSION,
                rootAvailable = false,
                rootAuthorized = false,
                rootShellAvailable = false,
                privilegeLevel = PrivilegeLevel.USER,
                lastError = probe.reason,
            )
            null -> RootHealth(
                state = RootProviderState.REQUIRES_PERMISSION,
                rootAvailable = false,
                rootAuthorized = false,
                rootShellAvailable = false,
                privilegeLevel = PrivilegeLevel.USER,
                lastError = "Device connected but root shell was not probed (call checkHealth(probeShell = true) to verify)",
            )
        }
    }

    override fun getCapabilities(): Set<String> {
        if (!isDeviceConnected()) return emptySet()
        val caps = mutableSetOf("adb_device_detection", "adb_root_shell_execute")
        if (getAndroidVersion() != null) caps += "adb_android_version_query"
        return caps
    }

    /**
     * Runs [command] as root via `adb shell su -c '<command>'`. See class doc for the two layers of
     * [shellQuote]ing this requires and why [RootCommand.workingDirectory]/[RootCommand.environment]
     * are folded into the remote command string rather than applied to the local `adb` process.
     */
    override fun execute(command: RootCommand, isCancelled: () -> Boolean): RootExecutionResult {
        val innerCommand = (listOf(command.executable) + command.args).joinToString(" ") { shellQuote(it) }
        val cdPrefix = command.workingDirectory?.let { "cd ${shellQuote(it)} && " } ?: ""
        val envPrefix = command.environment.entries.joinToString("") { (key, value) -> "$key=${shellQuote(value)} " }
        val remoteCommand = "$cdPrefix$envPrefix$innerCommand"

        val startedAt = System.currentTimeMillis()
        val result = runProcess(
            adbArgv() + listOf("shell", suExecutable, "-c", shellQuote(remoteCommand)),
            command.timeoutMillis,
            isCancelled,
        )
        return when (result) {
            is ProcessRunResult.Ran -> RootExecutionResult.Success(
                exitCode = result.exitCode,
                stdout = result.stdout,
                stderr = result.stderr,
                durationMillis = System.currentTimeMillis() - startedAt,
            )
            is ProcessRunResult.FailedToStart ->
                RootExecutionResult.Failure("Failed to start '$adbExecutable': ${result.reason}")
            ProcessRunResult.TimedOut -> RootExecutionResult.Failure("Command timed out after ${command.timeoutMillis}ms")
            ProcessRunResult.Cancelled -> RootExecutionResult.Failure("Command cancelled")
        }
    }

    private fun adbArgv(): List<String> = if (serial != null) listOf(adbExecutable, "-s", serial) else listOf(adbExecutable)

    private fun probeRootShellCached(): AdbRootShellProbe {
        val cached = cachedProbe
        val now = System.currentTimeMillis()
        if (cached != null && now - cached.first < cacheTtlMillis) return cached.second

        val fresh = probeRootShellNow()
        cachedProbe = now to fresh
        when (fresh) {
            is AdbRootShellProbe.Available -> logger.info("adb_root_shell_probe_result", mapOf("available" to "true", "uid" to fresh.uid))
            is AdbRootShellProbe.Unavailable -> logger.warn("adb_root_shell_probe_result", mapOf("available" to "false", "reason" to fresh.reason))
        }
        return fresh
    }

    private fun probeRootShellNow(): AdbRootShellProbe {
        val result = runProcess(adbArgv() + listOf("shell", suExecutable, "-c", shellQuote("id -u")), processTimeoutMillis)
        return when (result) {
            is ProcessRunResult.Ran -> {
                val uid = result.stdout.trim()
                if (result.exitCode == 0 && uid == "0") {
                    AdbRootShellProbe.Available(uid)
                } else {
                    AdbRootShellProbe.Unavailable("'$adbExecutable shell $suExecutable -c \"id -u\"' exited ${result.exitCode}, reported uid '$uid'")
                }
            }
            is ProcessRunResult.FailedToStart -> AdbRootShellProbe.Unavailable("'$adbExecutable' is not available: ${result.reason}")
            ProcessRunResult.TimedOut -> AdbRootShellProbe.Unavailable("root shell probe timed out after ${processTimeoutMillis}ms")
            ProcessRunResult.Cancelled -> AdbRootShellProbe.Unavailable("root shell probe cancelled")
        }
    }
}
