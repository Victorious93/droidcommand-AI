package ai.droidcommand.termux

import ai.droidcommand.security.ExecutionTarget

/**
 * Which terminal a command runs in, per the project owner's 2026-09-27 priority: the device's own
 * Termux first, then VictorSuite's Termux-based terminal, then DroidCommand's built-in one.
 */
enum class TerminalKind { TERMUX, VICTORSUITE, BUILT_IN }

/** The terminal chosen, the installed app's version when one was found, and why in plain words. */
data class TerminalChoice(val kind: TerminalKind, val versionName: String?, val reason: String)

/** What [TerminalSelector] needs to know about the device. [AdbTermuxExecutor] implements it over adb. */
interface InstalledTerminalProbe {
    /** Whether any app is installed under the Termux package name, `com.termux`. */
    fun isTermuxPackageInstalled(): Boolean

    /** That app's `versionName`, or null when unknown. */
    fun termuxPackageVersionName(): String?
}

/**
 * Picks a terminal. Termux and VictorSuite can't both be installed: VictorSuite is a Termux fork
 * that keeps the `com.termux` package name (its `app/build.gradle` sets `namespace "com.termux"`
 * and no separate `applicationId`), and its bundled packages are built for Termux's
 * `/data/data/com.termux` prefix, so renaming it isn't a small change. The priority therefore
 * comes down to: use whichever `com.termux` app is installed if it can run commands, otherwise the
 * built-in terminal.
 *
 * Telling the two apart is informational only; both accept the same `RUN_COMMAND` intent. It uses
 * the `versionName`: VictorSuite (from ZeroTermux) uses four numeric parts, such as `0.118.3.54`,
 * while Termux releases use three, optionally followed by a `+` or `-` suffix, such as `0.118.1` or
 * `0.118.0+github-debug`. That is a heuristic, not proof of who built the app: checking the APK's
 * signing certificate would be, and needs an APK signature parser verified against a real device
 * first. An unrecognised version is reported as Termux.
 */
object TerminalSelector {
    private val fourPartVersion = Regex("""^\d+\.\d+\.\d+\.\d+$""")

    fun identify(versionName: String?): TerminalKind =
        if (versionName != null && fourPartVersion.matches(versionName.trim())) TerminalKind.VICTORSUITE else TerminalKind.TERMUX

    /**
     * [usable] is asked only when a `com.termux` app is installed; it should say whether commands
     * can actually run there (for [AdbTermuxExecutor], its [TermuxExecutor.isAvailable]).
     */
    fun choose(probe: InstalledTerminalProbe, usable: () -> Boolean): TerminalChoice {
        if (!probe.isTermuxPackageInstalled()) {
            return TerminalChoice(TerminalKind.BUILT_IN, null, "Neither Termux nor VictorSuite is installed; using the built-in terminal")
        }
        val version = probe.termuxPackageVersionName()
        val kind = identify(version)
        val name = if (kind == TerminalKind.VICTORSUITE) "VictorSuite" else "Termux"
        val versionText = version?.let { " $it" }.orEmpty()
        if (!usable()) {
            return TerminalChoice(
                TerminalKind.BUILT_IN,
                version,
                "$name$versionText is installed but can't run commands from here (it needs allow-external-apps=true, " +
                    "and this backend needs root to read results); using the built-in terminal",
            )
        }
        return TerminalChoice(kind, version, "Using $name$versionText")
    }
}

/** A [TerminalChoice] and the [ExecutionTarget] that runs commands in it. */
class SelectedTerminal(val choice: TerminalChoice, val target: ExecutionTarget)

/**
 * Chooses a terminal with [TerminalSelector] and returns the matching target: [termuxTarget] for
 * Termux or VictorSuite (the same `RUN_COMMAND` path serves both), otherwise [builtInTarget].
 * [builtInTarget] is whatever the caller counts as DroidCommand's own terminal; on a PC driving a
 * phone that is typically a plain `adb shell` target, and inside the PLANNED Android app it would
 * be the app's own process shell. Neither factory is called unless its target is chosen.
 */
fun selectTerminalTarget(
    probe: InstalledTerminalProbe,
    termuxTarget: () -> ExecutionTarget,
    builtInTarget: () -> ExecutionTarget,
): SelectedTerminal {
    var termux: ExecutionTarget? = null
    val choice = TerminalSelector.choose(probe) { termuxTarget().also { termux = it }.isHealthy() }
    val target = if (choice.kind == TerminalKind.BUILT_IN) builtInTarget() else termux!!
    return SelectedTerminal(choice, target)
}
