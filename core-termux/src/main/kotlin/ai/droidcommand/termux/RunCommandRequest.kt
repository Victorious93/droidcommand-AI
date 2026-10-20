package ai.droidcommand.termux

/**
 * The extras a caller must put on a `RUN_COMMAND` Intent, minus the
 * Android [android.app.PendingIntent] itself (that object cannot exist
 * in this JVM module).
 *
 * [background] is always `true` when built by [from]: a foreground terminal
 * runner does not populate the result Bundle. Callers must still attach
 * [RunCommandTarget.extraPendingIntent] on the Android Intent.
 *
 * Package / action / extras prefix follow [target]. Live default is
 * [RunCommandTargets.TERMUX] (`com.termux`).
 */
data class RunCommandRequest(
    val path: String,
    val arguments: List<String>,
    val workdir: String? = RunCommandContract.DEFAULT_WORKDIR,
    val background: Boolean = true,
    val commandLabel: String? = null,
    val target: RunCommandTarget = RunCommandContract.target(),
    val packageName: String = target.packageName,
    val componentClass: String = target.serviceClass,
    val action: String = target.action,
) {
    init {
        require(path.isNotBlank()) { "RUN_COMMAND_PATH must not be blank" }
        require(background) {
            "RUN_COMMAND_BACKGROUND must be true — the app-shell runner is required to capture output into the PendingIntent Bundle"
        }
    }

    fun extrasWithoutPendingIntent(): Map<String, Any> = buildMap {
        put(target.extraPath, path)
        put(target.extraArguments, arguments)
        workdir?.let { put(target.extraWorkdir, it) }
        put(target.extraBackground, true)
        put(target.extraRunner, RunCommandContract.RUNNER_APP_SHELL)
        commandLabel?.let { put(target.extraCommandLabel, it) }
    }

    companion object {
        /**
         * Maps a [TermuxCommand] onto VictorSuite extras. Relative executables
         * are resolved under the active target's `usr/bin`.
         * [TermuxCommand.workingDirectory] defaults to that target's home.
         * Background is forced on.
         */
        fun from(
            command: TermuxCommand,
            commandLabel: String? = null,
            target: RunCommandTarget = RunCommandContract.target(),
        ): RunCommandRequest {
            val path = if (command.executable.startsWith("/")) {
                command.executable
            } else {
                target.usrBin(command.executable)
            }
            return RunCommandRequest(
                path = path,
                arguments = command.args,
                workdir = command.workingDirectory ?: target.homeDir,
                background = true,
                commandLabel = commandLabel,
                target = target,
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
        isCancelled: () -> Boolean,
    ): RunCommandResultBundle
}
