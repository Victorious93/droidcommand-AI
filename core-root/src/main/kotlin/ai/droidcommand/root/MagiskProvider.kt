package ai.droidcommand.root

import ai.droidcommand.agent.Logger
import ai.droidcommand.agent.NoOpLogger
import java.io.File

/** Default, real-world Magisk marker paths. Presence of any is a real (if not sufficient) signal Magisk is installed. */
val DEFAULT_MAGISK_MARKER_PATHS: List<String> = listOf(
    "/sbin/.magisk",
    "/data/adb/magisk",
    "/data/adb/magisk.db",
    "/system/bin/magisk",
    "/system/xbin/magisk",
)

private sealed class RootShellProbe {
    data class Available(val uid: String) : RootShellProbe()
    data class Unavailable(val reason: String) : RootShellProbe()
}

/**
 * A real, verifying [RootProvider] for Magisk. Per this repository's own
 * non-negotiable rule ("do not treat the existence of Magisk-related
 * files, packages, or binaries alone as proof that root is functional"),
 * detection is split into two genuinely different questions:
 *
 * - [isMagiskInstalled] — is Magisk present at all (marker file/dir, or a
 *   `magisk` executable that actually starts)? Cheap, passive, spawns no
 *   process capable of interacting with a real root shell.
 * - [RootExecutor.isRootAvailable] (this class's `isRootAvailable()`) /
 *   [isAuthorized] — has a real root shell actually been granted to this
 *   process? Only this answers "is root functional", and only this may
 *   spawn `su`. Cached for [cacheTtlMillis] (default 60s, DP-011's
 *   already-recommended cadence per `docs/AUDIT_2026-09-05.md`'s DP-011
 *   row) rather than re-probed on every call — [invalidateCache] forces a
 *   fresh probe.
 *
 * [magiskMarkerPaths]/[magiskExecutable]/[suExecutable]/[processTimeoutMillis]
 * are all constructor-injectable so tests can point them at real, controlled
 * fixtures (a temp-directory marker file, a fake `su`/`magisk` shell script)
 * rather than mocking process execution — the same "prove the boundary is
 * real" discipline `ProcessBuilderShellExecutorTest` already holds itself to,
 * just against a fixture instead of the real system binaries this JVM-only
 * environment doesn't have.
 *
 * Deliberately NOT implemented here: Magisk module list/inspect/enable/
 * disable/install/remove. Per this task's own rule ("do not implement fake
 * module management... only expose module operations that are genuinely
 * implemented and verified"), none of those can be verified in this
 * environment (no real Magisk install, no device), so none are stubbed —
 * `getCapabilities()` never advertises them. A future session with a real
 * rooted test device is the correct place to add them for real.
 */
class MagiskProvider(
    private val magiskMarkerPaths: List<String> = DEFAULT_MAGISK_MARKER_PATHS,
    private val magiskExecutable: String = "magisk",
    private val suExecutable: String = "su",
    private val processTimeoutMillis: Long = 5_000,
    private val cacheTtlMillis: Long = 60_000,
    private val logger: Logger = NoOpLogger,
) : RootProvider {
    @Volatile
    private var cachedProbe: Pair<Long, RootShellProbe>? = null

    override val info: RootProviderInfo
        get() = RootProviderInfo(providerId = "magisk", providerName = "Magisk", version = getMagiskVersion())

    /** Presence only — never claims root is functional. See class doc. */
    fun isMagiskInstalled(): Boolean {
        if (magiskMarkerPaths.any { File(it).exists() }) return true
        return runProcess(listOf(magiskExecutable, "-v"), processTimeoutMillis) is ProcessRunResult.Ran
    }

    fun getMagiskVersion(): String? {
        val result = runProcess(listOf(magiskExecutable, "-v"), processTimeoutMillis)
        if (result !is ProcessRunResult.Ran || result.exitCode != 0) return null
        return result.stdout.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
    }

    /** Forces the next root-shell check to re-probe rather than reuse a cached result. */
    fun invalidateCache() {
        cachedProbe = null
    }

    override fun isRootAvailable(): Boolean = probeRootShellCached() is RootShellProbe.Available

    override fun isAuthorized(): Boolean = isRootAvailable()

    override fun getPrivilegeLevel(): PrivilegeLevel = when (probeRootShellCached()) {
        is RootShellProbe.Available -> PrivilegeLevel.ROOT
        is RootShellProbe.Unavailable -> if (isMagiskInstalled()) PrivilegeLevel.USER else PrivilegeLevel.NONE
    }

    override fun checkHealth(probeShell: Boolean): RootHealth {
        if (!isMagiskInstalled()) {
            return RootHealth(
                state = RootProviderState.UNAVAILABLE,
                rootAvailable = false,
                rootAuthorized = false,
                rootShellAvailable = false,
                privilegeLevel = PrivilegeLevel.NONE,
                lastError = "Magisk not detected on this system",
            )
        }
        val probe = if (probeShell) probeRootShellCached() else cachedProbe?.second
        return when (probe) {
            is RootShellProbe.Available -> RootHealth(
                state = RootProviderState.AVAILABLE,
                rootAvailable = true,
                rootAuthorized = true,
                rootShellAvailable = true,
                privilegeLevel = PrivilegeLevel.ROOT,
                lastError = null,
            )
            is RootShellProbe.Unavailable -> RootHealth(
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
                lastError = "Magisk detected but root shell was not probed (call checkHealth(probeShell = true) to verify)",
            )
        }
    }

    override fun getCapabilities(): Set<String> {
        if (!isMagiskInstalled()) return emptySet()
        val caps = mutableSetOf("magisk_detection", "root_shell_execute")
        if (getMagiskVersion() != null) caps += "magisk_version_query"
        return caps
    }

    /**
     * Runs [command] as root via `su -c`. Every argument is individually
     * [shellQuote]d before being joined into the single string `su -c`
     * requires — see [shellQuote]'s doc comment; [MagiskProviderTest]
     * proves an argument containing shell metacharacters is passed through
     * literally rather than interpreted.
     *
     * [RootCommand.workingDirectory]/[RootCommand.environment] are applied
     * to the outer `su` process this method starts, exactly like
     * `ProcessBuilderShellExecutor` applies `ShellCommand`'s equivalent
     * fields — see [RootCommand]'s own doc comment for the honest caveat
     * that `su` itself decides whether either survives the elevation.
     */
    override fun execute(command: RootCommand, isCancelled: () -> Boolean): RootExecutionResult {
        val quotedCommand = (listOf(command.executable) + command.args).joinToString(" ") { shellQuote(it) }
        val startedAt = System.currentTimeMillis()
        val result = runProcess(
            listOf(suExecutable, "-c", quotedCommand),
            command.timeoutMillis,
            isCancelled,
            workingDirectory = command.workingDirectory,
            environment = command.environment,
        )
        return when (result) {
            is ProcessRunResult.Ran -> RootExecutionResult.Success(
                exitCode = result.exitCode,
                stdout = result.stdout,
                stderr = result.stderr,
                durationMillis = System.currentTimeMillis() - startedAt,
            )
            is ProcessRunResult.FailedToStart ->
                RootExecutionResult.Failure("Failed to start '$suExecutable': ${result.reason}")
            ProcessRunResult.TimedOut -> RootExecutionResult.Failure("Command timed out after ${command.timeoutMillis}ms")
            ProcessRunResult.Cancelled -> RootExecutionResult.Failure("Command cancelled")
        }
    }

    private fun probeRootShellCached(): RootShellProbe {
        val cached = cachedProbe
        val now = System.currentTimeMillis()
        if (cached != null && now - cached.first < cacheTtlMillis) return cached.second

        val fresh = probeRootShellNow()
        cachedProbe = now to fresh
        when (fresh) {
            is RootShellProbe.Available -> logger.info("root_shell_probe_result", mapOf("available" to "true", "uid" to fresh.uid))
            is RootShellProbe.Unavailable -> logger.warn("root_shell_probe_result", mapOf("available" to "false", "reason" to fresh.reason))
        }
        return fresh
    }

    private fun probeRootShellNow(): RootShellProbe =
        when (val result = runProcess(listOf(suExecutable, "-c", "id -u"), processTimeoutMillis)) {
            is ProcessRunResult.Ran -> {
                val uid = result.stdout.trim()
                if (result.exitCode == 0 && uid == "0") {
                    RootShellProbe.Available(uid)
                } else {
                    RootShellProbe.Unavailable("'$suExecutable -c \"id -u\"' exited ${result.exitCode}, reported uid '$uid'")
                }
            }
            is ProcessRunResult.FailedToStart -> RootShellProbe.Unavailable("'$suExecutable' is not available: ${result.reason}")
            ProcessRunResult.TimedOut -> RootShellProbe.Unavailable("root shell probe timed out after ${processTimeoutMillis}ms")
            ProcessRunResult.Cancelled -> RootShellProbe.Unavailable("root shell probe cancelled")
        }
}
