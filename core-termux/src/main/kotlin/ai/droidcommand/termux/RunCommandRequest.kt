package ai.droidcommand.termux

/**
 * The extras a caller must put on a `com.termux.RUN_COMMAND` Intent, minus
 * the Android [android.app.PendingIntent] itself (that object cannot exist
 * in this JVM module).
 *
 * [background] is always `true` when built by [from]: a foreground terminal
 * runner does not populate the result Bundle. Callers must still attach
 * [RunCommandContract.EXTRA_PENDING_INTENT] on the Android Intent.
 */
data class RunCommandRequest(
    val path: String,
    val arguments: List<String>,
    val workdir: String? = RunCommandContract.DEFAULT_WORKDIR,
    val background: Boolean = true,
    val commandLabel: String? = null,
    val packageName: String = RunCommandContract.PACKAGE,
    val componentClass: String = RunCommandContract.SERVICE_CLASS,
    val action: String = RunCommandContract.ACTION,
) {
    init {
        require(path.isNotBlank()) { "RUN_COMMAND_PATH must not be blank" }
        require(background) {
            "RUN_COMMAND_BACKGROUND must be true — the app-shell runner is required to capture output into the PendingIntent Bundle"
        }
    }

    fun extrasWithoutPendingIntent(): Map<String, Any> = buildMap {
        put(RunCommandContract.EXTRA_PATH, path)
        put(RunCommandContract.EXTRA_ARGUMENTS, arguments)
        workdir?.let { put(RunCommandContract.EXTRA_WORKDIR, it) }
        put(RunCommandContract.EXTRA_BACKGROUND, true)
        put(RunCommandContract.EXTRA_RUNNER, RunCommandContract.RUNNER_APP_SHELL)
        commandLabel?.let { put(RunCommandContract.EXTRA_COMMAND_LABEL, it) }
    }

    companion object {
        /**
         * Maps a [TermuxCommand] onto VictorSuite extras. Relative executables
         * are resolved under Termux's `usr/bin`. [TermuxCommand.workingDirectory]
         * defaults to VictorSuite's home. Background is forced on.
         */
        fun from(command: TermuxCommand, commandLabel: String? = null): RunCommandRequest {
            val path = if (command.executable.startsWith("/")) {
                command.executable
            } else {
                "/data/data/com.termux/files/usr/bin/${command.executable}"
            }
            return RunCommandRequest(
                path = path,
                arguments = command.args,
                workdir = command.workingDirectory ?: RunCommandContract.DEFAULT_WORKDIR,
                background = true,
                commandLabel = commandLabel,
            )
        }
    }
}

/**
 * Sends one [RunCommandRequest] and returns the VictorSuite result Bundle.
 * Android attaches the PendingIntent; tests fake this.
 */
fun interface RunCommandClient {
    fun execute(
        request: RunCommandRequest,
        timeoutMillis: Long,
        isCancelled: () -> Boolean = { false },
    ): RunCommandResultBundle
}
