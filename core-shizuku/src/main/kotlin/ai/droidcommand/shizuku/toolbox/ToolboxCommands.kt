package ai.droidcommand.shizuku.toolbox

import ai.droidcommand.shizuku.PrivilegedNames

enum class PackageFilter(val flag: String?, val label: String) {
    ALL(null, "All"),
    USER("-3", "Installed by you"),
    SYSTEM("-s", "System"),
    DISABLED("-d", "Disabled"),
}

enum class SettingsNamespace(val wire: String) { GLOBAL("global"), SECURE("secure"), SYSTEM("system") }

enum class RebootTarget(val label: String, val arg: String?) {
    SYSTEM("Restart", null),
    RECOVERY("Recovery", "recovery"),
    BOOTLOADER("Bootloader", "bootloader"),
}

enum class LogLevel(val letter: Char) { VERBOSE('V'), DEBUG('D'), INFO('I'), WARN('W'), ERROR('E') }

/**
 * Every System Toolbox command lives here so the exact argv is unit-tested and the confirmation dialog shows
 * exactly what runs. Inputs are validated; anything that could smuggle a second command is rejected.
 */
object ToolboxCommands {
    private val SETTING_KEY = Regex("^[A-Za-z0-9_.:-]{1,128}$")
    private val PERMISSION = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
    private val PROP = Regex("^[A-Za-z0-9_.-]{1,128}$")

    // Read-only.
    fun listPackages(filter: PackageFilter): ToolboxCommand =
        ToolboxCommand("List apps", listOfNotNull("pm", "list", "packages", filter.flag), destructive = false)

    fun packagePermissions(pkg: String): ToolboxBuild = pkgCommand(pkg) {
        ToolboxCommand("Show permissions", listOf("dumpsys", "package", it), destructive = false)
    }

    fun logcat(level: LogLevel, lines: Int): ToolboxCommand {
        val n = lines.coerceIn(10, 5_000)
        return ToolboxCommand("Read logcat", listOf("logcat", "-d", "-v", "time", "-t", n.toString(), "*:${level.letter}"), destructive = false)
    }

    fun getSetting(ns: SettingsNamespace, key: String): ToolboxBuild {
        if (!SETTING_KEY.matches(key)) return ToolboxBuild.Rejected("Setting names use letters, digits, _ . : - only.")
        return ToolboxBuild.Ok(ToolboxCommand("Read setting", listOf("settings", "get", ns.wire, key), destructive = false))
    }

    fun deviceProps(): ToolboxCommand = ToolboxCommand("Device props", listOf("getprop"), destructive = false)

    fun getProp(name: String): ToolboxBuild {
        if (!PROP.matches(name)) return ToolboxBuild.Rejected("Prop names use letters, digits, _ . - only.")
        return ToolboxBuild.Ok(ToolboxCommand("Read prop", listOf("getprop", name), destructive = false))
    }

    fun battery(): ToolboxCommand = ToolboxCommand("Battery", listOf("dumpsys", "battery"), destructive = false)

    fun storage(): ToolboxCommand = ToolboxCommand("Storage", listOf("df", "-h"), destructive = false)

    fun apkPath(pkg: String): ToolboxBuild = pkgCommand(pkg) {
        ToolboxCommand("Find APK", listOf("pm", "path", it), destructive = false)
    }

    /** Root file browser: list only. Root is required to see most system paths. */
    fun listDir(path: String): ToolboxBuild = pathCommand(path) {
        ToolboxCommand("List folder", listOf("ls", "-la", it), destructive = false, rootOnly = true)
    }

    /** Root file browser: first 64 KB of a file. */
    fun readFile(path: String): ToolboxBuild = pathCommand(path) {
        ToolboxCommand("Read file", listOf("head", "-c", "65536", it), destructive = false, rootOnly = true)
    }

    // Changes the phone: always confirmed with [ToolboxCommand.display].
    fun shell(command: String): ToolboxBuild {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return ToolboxBuild.Rejected("Type a command first.")
        if (trimmed.contains('\u0000')) return ToolboxBuild.Rejected("That command has a NUL byte.")
        return ToolboxBuild.Ok(ToolboxCommand("Run in shell", listOf("sh", "-c", trimmed), destructive = true, timeoutMs = 60_000))
    }

    fun install(apkPath: String): ToolboxBuild {
        if (!apkPath.endsWith(".apk")) return ToolboxBuild.Rejected("Pick an .apk file.")
        return pathCommand(apkPath) {
            ToolboxCommand("Install APK", listOf("pm", "install", "-r", it), destructive = true, timeoutMs = 120_000)
        }
    }

    fun uninstall(pkg: String): ToolboxBuild = pkgCommand(pkg) {
        ToolboxCommand("Uninstall", listOf("pm", "uninstall", "--user", "0", it), destructive = true)
    }

    fun disable(pkg: String): ToolboxBuild = pkgCommand(pkg) {
        ToolboxCommand("Disable (freeze)", listOf("pm", "disable-user", "--user", "0", it), destructive = true)
    }

    fun enable(pkg: String): ToolboxBuild = pkgCommand(pkg) {
        ToolboxCommand("Enable", listOf("pm", "enable", "--user", "0", it), destructive = true)
    }

    fun clearData(pkg: String): ToolboxBuild = pkgCommand(pkg) {
        ToolboxCommand("Clear data", listOf("pm", "clear", "--user", "0", it), destructive = true)
    }

    fun forceStop(pkg: String): ToolboxBuild = pkgCommand(pkg) {
        ToolboxCommand("Force stop", listOf("am", "force-stop", it), destructive = true)
    }

    fun grant(pkg: String, permission: String): ToolboxBuild = permCommand(pkg, permission, "grant")

    fun revoke(pkg: String, permission: String): ToolboxBuild = permCommand(pkg, permission, "revoke")

    fun putSetting(ns: SettingsNamespace, key: String, value: String): ToolboxBuild {
        if (!SETTING_KEY.matches(key)) return ToolboxBuild.Rejected("Setting names use letters, digits, _ . : - only.")
        if (value.any { it == '\n' || it == '\r' || it == '\u0000' }) return ToolboxBuild.Rejected("Values must be one line.")
        return ToolboxBuild.Ok(ToolboxCommand("Change setting", listOf("settings", "put", ns.wire, key, value), destructive = true))
    }

    fun reboot(target: RebootTarget): ToolboxCommand =
        ToolboxCommand("Reboot: ${target.label}", listOfNotNull("reboot", target.arg), destructive = true)

    /** Copies one APK file to [destDir]/<pkg>.apk. Run [apkPath] first and pass the first `package:` path. */
    fun backupApk(pkg: String, sourceApk: String, destDir: String): ToolboxBuild {
        if (!PrivilegedNames.isSafeAndroidName(pkg)) return ToolboxBuild.Rejected("That is not a package name.")
        if (!sourceApk.endsWith(".apk")) return ToolboxBuild.Rejected("Source is not an .apk path.")
        val bad = listOf(sourceApk, destDir).firstNotNullOfOrNull { pathProblem(it) }
        if (bad != null) return ToolboxBuild.Rejected(bad)
        val dest = destDir.trimEnd('/') + "/" + pkg + ".apk"
        return ToolboxBuild.Ok(ToolboxCommand("Back up APK", listOf("cp", sourceApk, dest), destructive = true, timeoutMs = 120_000))
    }

    /** Parses `pm path` output (`package:/data/app/.../base.apk`). */
    fun parseApkPaths(stdout: String): List<String> =
        stdout.lineSequence().map { it.trim() }.filter { it.startsWith("package:") }.map { it.removePrefix("package:") }.toList()

    /** Parses `pm list packages` output into sorted package names. */
    fun parsePackages(stdout: String): List<String> =
        stdout.lineSequence().map { it.trim() }.filter { it.startsWith("package:") }
            .map { it.removePrefix("package:") }.filter { PrivilegedNames.isSafeAndroidName(it) }.sorted().toList()

    private fun pkgCommand(pkg: String, build: (String) -> ToolboxCommand): ToolboxBuild =
        if (PrivilegedNames.isSafeAndroidName(pkg.trim())) ToolboxBuild.Ok(build(pkg.trim())) else ToolboxBuild.Rejected("That is not a package name.")

    private fun permCommand(pkg: String, permission: String, verb: String): ToolboxBuild {
        if (!PrivilegedNames.isSafeAndroidName(pkg)) return ToolboxBuild.Rejected("That is not a package name.")
        if (!PERMISSION.matches(permission) || permission.length > 256) return ToolboxBuild.Rejected("That is not a permission name.")
        val label = if (verb == "grant") "Grant permission" else "Revoke permission"
        return ToolboxBuild.Ok(ToolboxCommand(label, listOf("pm", verb, pkg, permission), destructive = true))
    }

    private fun pathCommand(path: String, build: (String) -> ToolboxCommand): ToolboxBuild {
        val problem = pathProblem(path)
        return if (problem == null) ToolboxBuild.Ok(build(path)) else ToolboxBuild.Rejected(problem)
    }

    internal fun pathProblem(path: String): String? = when {
        !path.startsWith("/") -> "Use a full path that starts with /."
        path.length > 1024 -> "That path is too long."
        path.any { it == '\n' || it == '\r' || it == '\u0000' } -> "Paths must be one line."
        path.startsWith("-") -> "Paths cannot start with -."
        else -> null
    }
}
