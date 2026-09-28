package ai.droidcommand.metasploit

/**
 * Which Metasploit module namespace a [MetasploitCommand] targets. Not
 * merely informational: [MetasploitCommand.init] requires [modulePath] to
 * start with this value's own lowercase name, so a caller can never send an
 * `EXPLOIT`-typed command against an `auxiliary/...` path (or vice versa)
 * by mistake.
 */
enum class MetasploitModuleType { EXPLOIT, AUXILIARY, POST, PAYLOAD }

/**
 * [host] is required and never blank — this tool has no "scan everything"
 * mode; a caller must name one authorized target per invocation. [port]
 * stays a typed `Int?` rather than a string precisely so it can never carry
 * an injection payload the way a raw string RPORT could.
 */
data class MetasploitTarget(val host: String, val port: Int? = null)

/**
 * A structured Metasploit module invocation, built entirely from typed
 * fields — never a raw msfconsole command string a caller (human or LLM)
 * types directly. This is the resource-script equivalent of
 * `core-shell.ShellCommand`'s argv-vector injection-safety guarantee:
 * [modulePath]/[payload] are checked against [MODULE_PATH_PATTERN], every
 * option key against [OPTION_KEY_PATTERN], and every interpolated value
 * (host, payload, option values) against [assertSafeInterpolationValue] —
 * so no field can smuggle a `;`-separated extra msfconsole command or a
 * resource-script line break into
 * [ShellBackedMetasploitExecutor]'s generated script.
 */
data class MetasploitCommand(
    val moduleType: MetasploitModuleType,
    val modulePath: String,
    val target: MetasploitTarget,
    val payload: String? = null,
    val options: Map<String, String> = emptyMap(),
    val timeoutMillis: Long = 120_000,
) {
    init {
        require(MODULE_PATH_PATTERN.matches(modulePath)) {
            "modulePath '$modulePath' does not match the required pattern ${MODULE_PATH_PATTERN.pattern}"
        }
        require(modulePath.startsWith("${moduleType.name.lowercase()}/")) {
            "modulePath '$modulePath' does not start with the expected '${moduleType.name.lowercase()}/' prefix for moduleType $moduleType"
        }
        require(target.host.isNotBlank()) {
            "target.host must not be blank — this tool never runs against an unspecified/mass target"
        }
        assertSafeInterpolationValue("target.host", target.host)
        payload?.let {
            require(MODULE_PATH_PATTERN.matches(it)) {
                "payload '$it' does not match the required module-path pattern ${MODULE_PATH_PATTERN.pattern}"
            }
        }
        options.forEach { (key, value) ->
            require(OPTION_KEY_PATTERN.matches(key)) {
                "option key '$key' is not a valid Metasploit datastore option name (expected ${OPTION_KEY_PATTERN.pattern})"
            }
            assertSafeInterpolationValue("option.$key", value)
        }
    }

    companion object {
        val MODULE_PATH_PATTERN = Regex("^[a-z0-9_./-]+$")
        val OPTION_KEY_PATTERN = Regex("^[A-Za-z0-9_]+$")

        private val UNSAFE_CHARS = charArrayOf(';', '\n', '\r', '\u0000')

        /**
         * Rejects a value that could inject an additional `;`-separated
         * msfconsole command (or a resource-script line break) into
         * [ShellBackedMetasploitExecutor]'s generated `-x` string — the
         * same structural injection-safety guarantee
         * `core-shell.ShellCommand`'s doc comment describes for argv
         * vectors, applied here to resource-script interpolation instead.
         */
        internal fun assertSafeInterpolationValue(fieldName: String, value: String) {
            require(value.none { it in UNSAFE_CHARS }) {
                "$fieldName contains a character (';', newline, or NUL) that could inject additional msfconsole commands"
            }
        }
    }
}

sealed class MetasploitExecutionResult {
    data class Success(val exitCode: Int, val stdout: String, val stderr: String, val durationMillis: Long) : MetasploitExecutionResult()
    data class Failure(val reason: String, val cause: Throwable? = null) : MetasploitExecutionResult()
}
