package ai.droidcommand.shizuku.bridge

/**
 * Wire contract with Victorious Bridge (the ShizukuV repo), version 1. This is a *copy* of the contract, not a
 * dependency: DCA builds without a ShizukuV checkout. `BridgeContractFixtureTest` checks this catalogue against
 * `bridge-contract-v1.json`, the same file ShizukuV's own tests check its service against.
 *
 * The bridge is a LOCAL privileged-access service. DCA keeps AI planning, approvals, audit and remote transport;
 * a remote task reaches the bridge only through DCA's authenticated pipeline and this class set, and remote
 * metadata (task id) is correlation only - it never authorises anything on the device.
 */
object BridgeContract {
    const val API_VERSION = 1
    const val APPLICATION_ID = "ai.victorious.bridge"
    const val PROVIDER_AUTHORITY_SUFFIX = ".victoriousbridge"
    const val CLIENT_SUPPORT_META_DATA = "ai.victorious.bridge.client.SUPPORT"
}

enum class BridgeCapability(val id: String) {
    DIAGNOSTICS_READ("diagnostics.read"),
    SETTINGS_READ("settings.read"),
    SETTINGS_WRITE("settings.write"),
    FILES_READ("files.read"),
    FILES_WRITE("files.write"),
    PACKAGES_READ("packages.read"),
    PACKAGES_MANAGE("packages.manage"),
    SHELL_EXEC("shell.exec"),
    ;

    companion object {
        fun fromId(id: String): BridgeCapability? = entries.firstOrNull { it.id == id }
    }
}

enum class ArgType { STRING, LONG, BOOL, BASE64_BYTES }

data class ArgSpec(val name: String, val type: ArgType, val required: Boolean = false)

/** One controlled operation and how DCA classifies it for its own approval policy. */
enum class BridgeOperation(
    val id: String, val capability: BridgeCapability, val description: String,
    val args: List<ArgSpec>, val mutating: Boolean,
) {
    DIAGNOSTICS("diagnostics.read", BridgeCapability.DIAGNOSTICS_READ, "Reads device diagnostics through Victorious Bridge.", emptyList(), false),
    SETTINGS_LIST("settings.list", BridgeCapability.SETTINGS_READ, "Lists the settings Victorious Bridge supports.", emptyList(), false),
    SETTINGS_GET("settings.get", BridgeCapability.SETTINGS_READ, "Reads one supported setting.", listOf(ArgSpec("namespace", ArgType.STRING, true), ArgSpec("key", ArgType.STRING, true)), false),
    SETTINGS_PUT("settings.put", BridgeCapability.SETTINGS_WRITE, "Changes one supported setting.", listOf(ArgSpec("namespace", ArgType.STRING, true), ArgSpec("key", ArgType.STRING, true), ArgSpec("value", ArgType.STRING, true)), true),
    FILES_STAT("files.stat", BridgeCapability.FILES_READ, "Inspects a file in an approved folder.", listOf(ArgSpec("path", ArgType.STRING, true)), false),
    FILES_LIST("files.list", BridgeCapability.FILES_READ, "Lists an approved folder.", listOf(ArgSpec("path", ArgType.STRING, true), ArgSpec("offset", ArgType.LONG), ArgSpec("limit", ArgType.LONG)), false),
    FILES_READ("files.read", BridgeCapability.FILES_READ, "Reads one bounded chunk of a file in an approved folder.", listOf(ArgSpec("path", ArgType.STRING, true), ArgSpec("offset", ArgType.LONG), ArgSpec("length", ArgType.LONG)), false),
    FILES_WRITE("files.write", BridgeCapability.FILES_WRITE, "Writes one bounded chunk to a file in an approved folder.", listOf(ArgSpec("path", ArgType.STRING, true), ArgSpec("data", ArgType.BASE64_BYTES, true), ArgSpec("offset", ArgType.LONG), ArgSpec("truncate", ArgType.BOOL), ArgSpec("create_only", ArgType.BOOL)), true),
    FILES_MKDIR("files.mkdir", BridgeCapability.FILES_WRITE, "Creates a folder in an approved folder.", listOf(ArgSpec("path", ArgType.STRING, true)), true),
    FILES_DELETE("files.delete", BridgeCapability.FILES_WRITE, "Deletes a file or empty folder in an approved folder.", listOf(ArgSpec("path", ArgType.STRING, true)), true),
    PACKAGES_LIST("packages.list", BridgeCapability.PACKAGES_READ, "Lists installed apps.", listOf(ArgSpec("filter", ArgType.STRING), ArgSpec("user", ArgType.LONG)), false),
    PACKAGES_INFO("packages.info", BridgeCapability.PACKAGES_READ, "Inspects one installed app.", listOf(ArgSpec("package", ArgType.STRING, true)), false),
    PACKAGES_SET_ENABLED("packages.setEnabled", BridgeCapability.PACKAGES_MANAGE, "Enables or disables an app.", listOf(ArgSpec("package", ArgType.STRING, true), ArgSpec("enabled", ArgType.BOOL, true), ArgSpec("user", ArgType.LONG)), true),
    PACKAGES_FORCE_STOP("packages.forceStop", BridgeCapability.PACKAGES_MANAGE, "Force-stops an app.", listOf(ArgSpec("package", ArgType.STRING, true), ArgSpec("user", ArgType.LONG)), true),
    SHELL_EXEC("shell.exec", BridgeCapability.SHELL_EXEC, "Runs an unrestricted shell command through Victorious Bridge.", listOf(ArgSpec("command", ArgType.STRING, true), ArgSpec("timeout_ms", ArgType.LONG)), true),
    ;

    companion object {
        fun fromId(id: String): BridgeOperation? = entries.firstOrNull { it.id == id }
    }
}

enum class BridgeError {
    NOT_ATTACHED,
    DENIED,
    EXPIRED,
    REVOKED,
    IDENTITY_MISMATCH,
    APPROVAL_DENIED,
    APPROVAL_TIMEOUT,
    UNSUPPORTED_CAPABILITY,
    UNSUPPORTED_OPERATION,
    INVALID_ARGUMENT,
    PATH_OUT_OF_SCOPE,
    NOT_FOUND,
    BACKEND_POLICY,
    CANCELLED,
    TIMEOUT,
    IO_ERROR,
    EXEC_FAILED,
    TOO_LARGE,
    OVERLOADED,
    INTERNAL,
}

/** Which backend an operation may run on. A mismatch fails; DCA never asks the bridge to "try something stronger". */
enum class BridgeBackend(val id: String) { ANY("any"), ADB_ONLY("adb"), ROOT_ONLY("root") }
