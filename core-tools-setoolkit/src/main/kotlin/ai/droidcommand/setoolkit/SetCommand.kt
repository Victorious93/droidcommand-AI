package ai.droidcommand.setoolkit

/**
 * The Social-Engineer Toolkit (SET) attack-vector categories its own menu
 * exposes. Purely a classification field on [SetCommand] today — unlike
 * [ai.droidcommand.metasploit.MetasploitModuleType], nothing here asserts a
 * specific automation-config shape per vector, since [ProcessBackedSetExecutor]
 * deliberately does not claim to know SET's exact non-interactive syntax
 * (see that class's doc comment).
 */
enum class SetAttackVector {
    SPEAR_PHISHING,
    WEBSITE_ATTACK,
    INFECTIOUS_MEDIA_GENERATOR,
    MASS_MAILER,
    QRCODE_GENERATOR,
    POWERSHELL_ATTACK,
    SMS_SPOOFING,
}

/**
 * Exactly one of [host]/[emailAddress] must be set — [SetCommand.init]
 * enforces this so the tool can never be invoked against an unnamed/mass
 * target, mirroring [ai.droidcommand.metasploit.MetasploitTarget]'s
 * required, non-blank host.
 */
data class SetTarget(val host: String? = null, val emailAddress: String? = null)

/**
 * A structured SET attack-vector invocation, built entirely from typed
 * fields — never a raw string a caller types. Every interpolated value
 * (host, email, payload, option values) is checked by [assertSafeValue] so
 * none can inject an extra line into the automation config
 * [ProcessBackedSetExecutor] writes to disk.
 */
data class SetCommand(
    val attackVector: SetAttackVector,
    val target: SetTarget,
    val payload: String? = null,
    val options: Map<String, String> = emptyMap(),
    val timeoutMillis: Long = 180_000,
) {
    init {
        require(target.host != null || target.emailAddress != null) {
            "SetCommand requires an explicit target.host or target.emailAddress — this tool never runs against an unspecified/mass target"
        }
        target.host?.let {
            require(it.isNotBlank()) { "target.host must not be blank" }
            assertSafeValue("target.host", it)
        }
        target.emailAddress?.let {
            require(it.isNotBlank()) { "target.emailAddress must not be blank" }
            assertSafeValue("target.emailAddress", it)
        }
        payload?.let { assertSafeValue("payload", it) }
        options.forEach { (key, value) ->
            require(OPTION_KEY_PATTERN.matches(key)) {
                "option key '$key' is not a valid SET automation config key (expected ${OPTION_KEY_PATTERN.pattern})"
            }
            assertSafeValue("option.$key", value)
        }
    }

    companion object {
        val OPTION_KEY_PATTERN = Regex("^[A-Za-z0-9_]+$")

        private val UNSAFE_CHARS = charArrayOf('\n', '\r', '\u0000')

        /**
         * Rejects a value that could inject an additional line into the
         * newline-separated automation config [ProcessBackedSetExecutor]
         * writes to disk.
         */
        internal fun assertSafeValue(fieldName: String, value: String) {
            require(value.none { it in UNSAFE_CHARS }) {
                "$fieldName contains a newline or NUL byte that could inject an additional automation-config line"
            }
        }
    }
}

sealed class SetExecutionResult {
    data class Success(val exitCode: Int, val stdout: String, val stderr: String, val durationMillis: Long) : SetExecutionResult()
    data class Failure(val reason: String, val cause: Throwable? = null) : SetExecutionResult()
}
