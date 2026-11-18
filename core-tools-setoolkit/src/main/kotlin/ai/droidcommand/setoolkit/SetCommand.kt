package ai.droidcommand.setoolkit

/**
 * The Social-Engineer Toolkit (SET) attack-vector categories its own menu
 * exposes. Purely a classification field on [SetCommand] today — unlike
 * [ai.droidcommand.metasploit.MetasploitModuleType], nothing here asserts a
 * specific automation-config shape per vector, since [ProcessBackedSetExecutor]
 * deliberately does not claim to know SET's exact non-interactive syntax
 * (see that class's doc comment). [SetMenuPath] maps each entry to the
 * digits SET's own main menu uses to reach it.
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
 * (host, email, payload, option values, menu answers) is checked by
 * [assertSafeValue] so none can inject an extra line into whatever
 * newline-separated text a real [SetExecutor] writes to disk or pipes to
 * a process's stdin.
 *
 * [target]/[payload]/[options] are this tool's policy surface: what
 * [SetTool] validates and what `core-security` gates on. They are not
 * automatically typed into a live SET session — the exact menu SET shows
 * after a vector is chosen genuinely differs by version and by vector
 * (spear-phishing's next prompt is not website-attack's), and this
 * codebase's standing rule is to say so rather than guess. [menuAnswers]
 * is the explicit, ordered, literal sequence of answers SET would see
 * after [SetMenuPath]'s best-effort navigation digits — including the
 * target/payload values again, wherever the operator's own SET session
 * actually asks for them. [TermuxSetExecutor] shows the exact sequence it
 * is about to send before it is sent, so a wrong assumption about where a
 * prompt falls is caught by the operator, not silently mistargeted.
 */
data class SetCommand(
    val attackVector: SetAttackVector,
    val target: SetTarget,
    val payload: String? = null,
    val options: Map<String, String> = emptyMap(),
    val menuAnswers: List<String> = emptyList(),
    /**
     * Whether [TermuxSetExecutor] should prepend [SetMenuPath]'s best-effort main-menu navigation digits before
     * [menuAnswers]. Default true. Set false when the operator already knows their installed version's menu and has
     * put the right digits at the start of [menuAnswers] themselves.
     */
    val useMenuPath: Boolean = true,
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
        menuAnswers.forEachIndexed { index, answer -> assertSafeValue("menuAnswers[$index]", answer) }
    }

    companion object {
        val OPTION_KEY_PATTERN = Regex("^[A-Za-z0-9_]+$")

        private val UNSAFE_CHARS = charArrayOf('\n', '\r', '\u0000')

        /**
         * Rejects a value that could inject an additional line into the
         * newline-separated automation config [ProcessBackedSetExecutor]
         * writes to disk, or an extra keystroke into the stdin sequence
         * [TermuxSetExecutor] pipes into a live `setoolkit` session.
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
