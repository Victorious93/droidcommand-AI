package ai.droidcommand.termux

/**
 * The `result` Bundle VictorSuite delivers through `RUN_COMMAND_PENDING_INTENT`
 * on `RESULT_OK`.
 *
 * **[Certain]** field names and the `err` / `exitCode` distinction come from
 * VictorSuite's documented contract:
 * - launch/validation failure: [err] is set and is not `0` or `-1`; read [errmsg]
 * - command actually ran: [exitCode] is present
 * - command failure: [exitCode] != 0 and [err] is OK (`0`/`-1`/absent)
 *
 * Streams that exceed the binder cap are trimmed from the **front** (tail kept).
 * [stdoutOriginalLength] / [stderrOriginalLength] are the true sizes as strings.
 */
data class RunCommandResultBundle(
    val stdout: String = "",
    val stdoutOriginalLength: String? = null,
    val stderr: String = "",
    val stderrOriginalLength: String? = null,
    val exitCode: Int? = null,
    val err: Int? = null,
    val errmsg: String? = null,
) {
    fun toExecutionResult(durationMillis: Long): TermuxExecutionResult {
        if (!RunCommandContract.isLaunchOk(err)) {
            val detail = errmsg?.takeIf { it.isNotBlank() } ?: "no errmsg"
            return TermuxExecutionResult.Failure(
                "VictorSuite RUN_COMMAND launch/validation failure (err=$err): $detail",
            )
        }
        val code = exitCode ?: return TermuxExecutionResult.Failure(
            "VictorSuite RUN_COMMAND returned no exitCode; the command did not actually run" +
                (errmsg?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""),
        )
        return TermuxExecutionResult.Success(
            exitCode = code,
            stdout = annotateStream(stdout, stdoutOriginalLength),
            stderr = annotateStream(stderr, stderrOriginalLength),
            durationMillis = durationMillis,
        )
    }

    companion object {
        /**
         * Builds a bundle from a string/boxed-number map so JVM tests can
         * exercise the same parse path the Android client uses on a real Bundle.
         */
        fun fromMap(values: Map<String, Any?>): RunCommandResultBundle = RunCommandResultBundle(
            stdout = stringValue(values[RunCommandContract.RESULT_STDOUT]).orEmpty(),
            stdoutOriginalLength = stringValue(values[RunCommandContract.RESULT_STDOUT_ORIGINAL_LENGTH]),
            stderr = stringValue(values[RunCommandContract.RESULT_STDERR]).orEmpty(),
            stderrOriginalLength = stringValue(values[RunCommandContract.RESULT_STDERR_ORIGINAL_LENGTH]),
            exitCode = intValue(values[RunCommandContract.RESULT_EXIT_CODE]),
            err = intValue(values[RunCommandContract.RESULT_ERR]),
            errmsg = stringValue(values[RunCommandContract.RESULT_ERRMSG]),
        )

        fun timeout(timeoutMillis: Long): RunCommandResultBundle = RunCommandResultBundle(
            err = 1,
            errmsg = "Timed out after ${timeoutMillis}ms waiting for VictorSuite RUN_COMMAND_PENDING_INTENT result",
        )

        fun cancelled(): RunCommandResultBundle = RunCommandResultBundle(
            err = 1,
            errmsg = "Cancelled while waiting for VictorSuite RUN_COMMAND_PENDING_INTENT result",
        )

        private fun stringValue(value: Any?): String? = when (value) {
            null -> null
            is String -> value
            else -> value.toString()
        }

        private fun intValue(value: Any?): Int? = when (value) {
            null -> null
            is Int -> value
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        }

        private fun annotateStream(text: String, originalLength: String?): String {
            val original = originalLength?.toLongOrNull() ?: return text
            if (original <= text.length) return text
            val note = "[trimmed from front; original_length=$original, delivered=${text.length}]"
            return if (text.isEmpty()) note else "$text\n$note"
        }
    }
}
