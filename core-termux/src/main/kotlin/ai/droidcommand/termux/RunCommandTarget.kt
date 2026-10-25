package ai.droidcommand.termux

/**
 * One RUN_COMMAND identity: package, action, component, caller permission,
 * and the `RUN_COMMAND_*` extras/result prefix.
 *
 * Envelope extras (`ai.droidcommand.REMOTE_ENVELOPE_JSON` /
 * `ai.droidcommand.REMOTE_RESPONSE_JSON`) are **not** part of this prefix
 * — they stay on the DroidCommand namespace either way.
 */
data class RunCommandTarget(
    val packageName: String,
    val action: String,
    val serviceClass: String,
    val permission: String,
    val extrasPrefix: String,
) {
    init {
        require(packageName.isNotBlank()) { "packageName is required" }
        require(action.isNotBlank()) { "action is required" }
        require(serviceClass.isNotBlank()) { "serviceClass is required" }
        require(permission.isNotBlank()) { "permission is required" }
        require(extrasPrefix.endsWith('.')) { "extrasPrefix must end with '.'" }
    }

    /** `package/class` component for `am -n` / `Intent.setComponent`. */
    val component: String get() = "$packageName/$serviceClass"

    val homeDir: String get() = "/data/data/$packageName/files/home"
    val usrDir: String get() = "/data/data/$packageName/files/usr"
    val bashPath: String get() = "$usrDir/bin/bash"
    val prootDistroPath: String get() = "$usrDir/bin/proot-distro"

    fun usrBin(executable: String): String = "$usrDir/bin/$executable"

    fun extra(suffix: String): String = extrasPrefix + suffix

    val extraPath: String get() = extra("RUN_COMMAND_PATH")
    val extraArguments: String get() = extra("RUN_COMMAND_ARGUMENTS")
    val extraWorkdir: String get() = extra("RUN_COMMAND_WORKDIR")
    val extraBackground: String get() = extra("RUN_COMMAND_BACKGROUND")
    val extraRunner: String get() = extra("RUN_COMMAND_RUNNER")
    val extraPendingIntent: String get() = extra("RUN_COMMAND_PENDING_INTENT")
    val extraCommandLabel: String get() = extra("RUN_COMMAND_COMMAND_LABEL")
    val extraResultDirectory: String get() = extra("RUN_COMMAND_RESULT_DIRECTORY")
    val extraResultSingleFile: String get() = extra("RUN_COMMAND_RESULT_SINGLE_FILE")
    val extraResultFileBasename: String get() = extra("RUN_COMMAND_RESULT_FILE_BASENAME")
}

/**
 * Live Termux identity vs the pending VictorSuite package rename
 * (VictorSuite PR #51, draft). [TERMUX] is the default until
 * [VictorSuiteRepackageFlag] is flipped.
 */
object RunCommandTargets {
    const val SERVICE_CLASS = "com.termux.app.RunCommandService"

    val TERMUX = RunCommandTarget(
        packageName = "com.termux",
        action = "com.termux.RUN_COMMAND",
        serviceClass = SERVICE_CLASS,
        permission = "com.termux.permission.RUN_COMMAND",
        extrasPrefix = "com.termux.",
    )

    val VICTORSUITE_REPACKAGE = RunCommandTarget(
        packageName = "com.victorious.suite",
        action = "com.victorious.suite.RUN_COMMAND",
        serviceClass = SERVICE_CLASS,
        permission = "com.victorious.suite.permission.RUN_COMMAND",
        extrasPrefix = "com.victorious.suite.",
    )
}
