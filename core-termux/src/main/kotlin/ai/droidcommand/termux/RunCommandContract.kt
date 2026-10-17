package ai.droidcommand.termux

/**
 * VictorSuite / Termux `com.termux.RUN_COMMAND` extras and result keys.
 *
 * **[Certain]** — this is the contract VictorSuite documented for the Kali
 * first-win. On-device callers must use [EXTRA_PENDING_INTENT] for results
 * (no ADB, no root, no terminal scraping). [EXTRA_BACKGROUND] must be true
 * so the app-shell runner captures stdout/stderr into the result Bundle;
 * a foreground terminal session paints the screen instead and does not
 * fill the Bundle.
 *
 * The PC-over-adb topology ([AdbTermuxExecutor]) cannot hold a
 * [android.app.PendingIntent] and is unchanged.
 */
object RunCommandContract {
    const val ACTION = "com.termux.RUN_COMMAND"
    const val PACKAGE = "com.termux"
    const val SERVICE_CLASS = "com.termux.app.RunCommandService"
    const val PERMISSION = "com.termux.permission.RUN_COMMAND"

    const val EXTRA_PATH = "com.termux.RUN_COMMAND_PATH"
    const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
    const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    const val EXTRA_RUNNER = "com.termux.RUN_COMMAND_RUNNER"
    const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"
    const val EXTRA_COMMAND_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL"
    const val RUNNER_APP_SHELL = "app-shell"
    const val EXTRA_RESULT_DIRECTORY = "com.termux.RUN_COMMAND_RESULT_DIRECTORY"
    const val EXTRA_RESULT_SINGLE_FILE = "com.termux.RUN_COMMAND_RESULT_SINGLE_FILE"
    const val EXTRA_RESULT_FILE_BASENAME = "com.termux.RUN_COMMAND_RESULT_FILE_BASENAME"

    /** Bundle extra on the Intent VictorSuite delivers through the PendingIntent. */
    const val RESULT_BUNDLE_EXTRA = "result"

    const val RESULT_STDOUT = "stdout"
    const val RESULT_STDOUT_ORIGINAL_LENGTH = "stdout_original_length"
    const val RESULT_STDERR = "stderr"
    const val RESULT_STDERR_ORIGINAL_LENGTH = "stderr_original_length"
    const val RESULT_EXIT_CODE = "exitCode"
    const val RESULT_ERR = "err"
    const val RESULT_ERRMSG = "errmsg"

    const val DEFAULT_WORKDIR = "/data/data/com.termux/files/home"
    const val PROOT_DISTRO_PATH = "/data/data/com.termux/files/usr/bin/proot-distro"
    const val BASH_PATH = "/data/data/com.termux/files/usr/bin/bash"

    /** Binder transaction cap for the PendingIntent result path. */
    const val TRANSACTION_SIZE_LIMIT_BYTES = 100 * 1024
    const val SINGLE_STREAM_LIMIT_BYTES = 100 * 1024
    const val BOTH_STREAMS_LIMIT_BYTES = 50 * 1024
    const val ERRMSG_LIMIT_BYTES = 25 * 1024

    /**
     * `err` values that mean the RUN_COMMAND launch itself succeeded.
     * Any other non-null `err` is a launch/validation failure; read `errmsg`.
     */
    fun isLaunchOk(err: Int?): Boolean = err == null || err == 0 || err == -1
}
