package ai.droidcommand.cli

import ai.droidcommand.root.AdbRootExecutor
import ai.droidcommand.termux.AdbTermuxExecutor
import ai.droidcommand.termux.NullTermuxExecutor
import ai.droidcommand.termux.TerminalKind
import ai.droidcommand.termux.TerminalSelector
import ai.droidcommand.termux.TermuxExecutor

/**
 * Where a phone is reached over adb: the adb binary and, when several devices are attached, the
 * serial to use. Off unless [ADB_ENV] is set, so the CLI never touches a connected phone by
 * surprise.
 */
internal data class AdbDeviceConfig(val adbExecutable: String = "adb", val serial: String? = null) {
    fun termuxExecutor(): AdbTermuxExecutor =
        AdbTermuxExecutor(rootExecutor = AdbRootExecutor(adbExecutable, serial), adbExecutable = adbExecutable, serial = serial)

    companion object {
        /** Reads [ADB_ENV], [ADB_SERIAL_ENV] and [ADB_PATH_ENV]; null unless [ADB_ENV] is `1` or `true`. */
        fun fromEnvironment(env: (String) -> String? = System::getenv): AdbDeviceConfig? {
            val enabled = env(ADB_ENV)?.trim()?.lowercase()
            if (enabled != "1" && enabled != "true") return null
            return AdbDeviceConfig(
                adbExecutable = env(ADB_PATH_ENV)?.takeIf { it.isNotBlank() } ?: "adb",
                serial = env(ADB_SERIAL_ENV)?.takeIf { it.isNotBlank() },
            )
        }
    }
}

internal const val ADB_ENV = "DROIDCOMMAND_CLI_ADB"
internal const val ADB_SERIAL_ENV = "DROIDCOMMAND_CLI_ADB_SERIAL"
internal const val ADB_PATH_ENV = "DROIDCOMMAND_CLI_ADB_PATH"

/**
 * The executor behind `run_termux_command`: the connected phone's Termux or VictorSuite when
 * [device] is set, otherwise [NullTermuxExecutor], which fails cleanly. The terminal is chosen per
 * call by [AdbTermuxExecutor] itself, since both apps share the `com.termux` package and the same
 * `RUN_COMMAND` path. There is no built-in terminal over adb yet, so when neither app is usable
 * the call fails with the reason [TerminalSelector] gives.
 */
internal fun termuxExecutorFor(device: AdbDeviceConfig?): TermuxExecutor = device?.termuxExecutor() ?: NullTermuxExecutor()

/**
 * `device-terminal [--adb <path>] [--serial <serial>]`: reports which terminal commands would run
 * in on the adb-connected phone, and why. Read-only: it checks the device, the installed
 * `com.termux` app and its settings, and runs nothing in the terminal itself.
 */
internal fun runDeviceTerminal(rest: List<String>): Int {
    var config = AdbDeviceConfig.fromEnvironment() ?: AdbDeviceConfig()
    var i = 0
    while (i < rest.size) {
        when (val arg = rest[i]) {
            "--adb" -> config = config.copy(adbExecutable = rest.getOrNull(++i) ?: return usageError("--adb requires a path"))
            "--serial" -> config = config.copy(serial = rest.getOrNull(++i) ?: return usageError("--serial requires a value"))
            else -> return usageError("Unknown device-terminal option '$arg'")
        }
        i++
    }
    val executor = config.termuxExecutor()
    if (!executor.isDeviceConnected()) {
        System.err.println("No authorized adb device connected${config.serial?.let { " with serial $it" }.orEmpty()}")
        return 1
    }
    val choice = TerminalSelector.choose(executor, executor::isAvailable)
    println(choice.reason)
    if (choice.kind == TerminalKind.BUILT_IN) {
        println("DroidCommand's own terminal isn't available over adb yet, so run_termux_command would fail on this phone.")
        return 1
    }
    return 0
}
