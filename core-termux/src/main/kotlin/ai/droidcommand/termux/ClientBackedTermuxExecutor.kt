package ai.droidcommand.termux

/**
 * [TermuxExecutor] that talks VictorSuite RUN_COMMAND through an injected
 * [RunCommandClient]. This is the on-device path: the Android client attaches
 * `RUN_COMMAND_PENDING_INTENT` and waits for the result Bundle. JVM tests
 * fake the client.
 *
 * [isAvailable] is the injected probe (package present, permission, etc.).
 * This class never claims msfconsole or nmap ran; it only reports what the
 * result Bundle said.
 */
class ClientBackedTermuxExecutor(
    private val client: RunCommandClient,
    private val probeAvailable: () -> Boolean,
    private val commandLabel: String? = null,
) : TermuxExecutor {
    override fun isAvailable(): Boolean = probeAvailable()

    override fun execute(command: TermuxCommand, isCancelled: () -> Boolean): TermuxExecutionResult {
        if (!isAvailable()) {
            val target = RunCommandContract.target()
            return TermuxExecutionResult.Failure(
                "VictorSuite RUN_COMMAND is not available (is ${target.packageName} installed, " +
                    "allow-external-apps=true, and ${target.permission} granted?)",
            )
        }
        val request = RunCommandRequest.from(command, commandLabel)
        val startedAt = System.currentTimeMillis()
        val bundle = client.execute(request, command.timeoutMillis, isCancelled)
        return bundle.toExecutionResult(System.currentTimeMillis() - startedAt)
    }
}
