package ai.droidcommand.termux

/**
 * VictorSuite PR #51 (draft, pending Don) will change `applicationId` from
 * `com.termux` to `com.victorious.suite`. Until that merges **and**
 * bootstraps are rebuilt, the live RUN_COMMAND / TERMUX_IPC binding stays
 * on `com.termux`. Intents sent to `com.termux` still reach real Termux,
 * not VictorSuite.
 *
 * **Default is off.** Flip only after Fleet Orchestrator go-signal +
 * bootstrap rebuild, in any of these ways (first match wins):
 * 1. Env `DROIDCOMMAND_VICTORSUITE_REPACKAGE=true` (also accepts `1`)
 * 2. JVM system property `droidcommand.victorsuite.repackage=true`
 * 3. Change [ENABLED_BY_DEFAULT] to `true` in a follow-up PR
 *
 * Call sites that need a deterministic value in tests pass
 * `useVictorSuiteRepackage` instead of reading the process environment.
 */
object VictorSuiteRepackageFlag {
    const val CONFIG_KEY = "DROIDCOMMAND_VICTORSUITE_REPACKAGE"
    const val SYSTEM_PROPERTY = "droidcommand.victorsuite.repackage"

    /** Live default. Do not flip until FO go-signal + bootstrap rebuild. */
    const val ENABLED_BY_DEFAULT = false

    fun isEnabled(
        getenv: (String) -> String? = System::getenv,
        getProperty: (String) -> String? = System::getProperty,
    ): Boolean {
        parse(getenv(CONFIG_KEY))?.let { return it }
        parse(getProperty(SYSTEM_PROPERTY))?.let { return it }
        parse(getProperty(CONFIG_KEY))?.let { return it }
        return ENABLED_BY_DEFAULT
    }

    private fun parse(raw: String?): Boolean? {
        val value = raw?.trim()?.lowercase() ?: return null
        if (value.isEmpty()) return null
        return when (value) {
            "1", "true", "yes" -> true
            "0", "false", "no" -> false
            else -> null
        }
    }
}
