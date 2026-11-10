package ai.droidcommand.remotepc

/**
 * Names from VictorSuite's frozen victorpc spec v1 (`docs/specs/VICTORPC_PAIRING.md`, §5 and §7).
 * Copied by hand: a change here must match a spec change in Victorious93/VictorSuite.
 */
object PcProfileContract {
    const val VICTORSUITE_PACKAGE = "com.termux"

    /** §5: exported, read-only, access checked in VictorSuite's code. */
    const val AUTHORITY = "com.termux.victor.pc"
    const val PATH_PROFILES = "profiles"
    const val PROFILES_URI = "content://$AUTHORITY/$PATH_PROFILES"

    const val SCHEMA_VERSION = 1

    /** §5 `call(method = "consent_status")` → Bundle key [CONSENT_KEY] = granted | required. */
    const val METHOD_CONSENT_STATUS = "consent_status"
    const val CONSENT_KEY = "consent"
    const val CONSENT_GRANTED = "granted"
    const val CONSENT_REQUIRED = "required"

    /** §7.1 / §7.2 actions, both targeted at [VICTORSUITE_PACKAGE]. */
    const val ACTION_OPEN_PC = "com.termux.victor.action.OPEN_PC"
    const val ACTION_PC_CONSENT = "com.termux.victor.action.PC_CONSENT"
    const val EXTRA_PROFILE_ID = "profileId"
    const val EXTRA_MODE = "mode"
    const val EXTRA_PATH = "path"

    /** §7.1 RESULT_CANCELED carries this String extra. */
    const val RESULT_EXTRA_ERROR = "error"

    object Columns {
        const val SCHEMA_VERSION = "schema_version"
        const val PROFILE_ID = "profile_id"
        const val NAME = "name"
        const val USER = "user"
        const val HOSTS = "hosts"
        const val PORT = "port"
        const val HOST_KEYS = "host_keys"
        const val CAPS = "caps"
        const val PAIRED_AT = "paired_at"
        const val LAST_SEEN_HOST = "last_seen_host"
        const val LAST_SEEN_AT = "last_seen_at"
        const val REACHABLE = "reachable"
        const val CALLER_ENROLLED = "caller_enrolled"
    }
}
