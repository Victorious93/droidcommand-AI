package ai.droidcommand.shizuku.toolbox

import ai.droidcommand.shizuku.PrivilegedNames

/** How a toolbox command runs: through the Shizuku UserService (adb shell uid) or through `su`. */
enum class ToolboxBackend { SHIZUKU, ROOT }

/**
 * One command the System Toolbox can run. [argv] is exactly what is executed;
 * [display] is the same command, shell-quoted, shown verbatim in the
 * confirmation dialog. [changesSystem] commands always need that confirmation.
 */
data class ToolboxCommand(
    val title: String,
    val argv: List<String>,
    val changesSystem: Boolean,
    val needsRoot: Boolean = false,
) {
    init {
        require(argv.isNotEmpty()) { "empty command" }
    }

    val display: String get() = argv.joinToString(" ") { ToolboxQuoting.quote(it) }

    /** Root runs as `su -c '<display>'`; Shizuku runs [argv] directly. */
    fun argvFor(backend: ToolboxBackend): List<String> = when (backend) {
        ToolboxBackend.SHIZUKU -> argv
        ToolboxBackend.ROOT -> listOf("su", "-c", display)
    }

    fun displayFor(backend: ToolboxBackend): String = argvFor(backend).joinToString(" ") { ToolboxQuoting.quote(it) }
}

object ToolboxQuoting {
    private val SAFE = Regex("^[A-Za-z0-9_@%+=:,./-]+$")

    fun quote(arg: String): String = if (arg.isNotEmpty() && SAFE.matches(arg)) arg else "'" + arg.replace("'", "'\\''") + "'"
}

enum class SettingsNamespace(val wire: String) { GLOBAL("global"), SECURE("secure"), SYSTEM("system") }

enum class RebootTarget(val label: String, val arg: String?) {
    SYSTEM("Reboot", null),
    RECOVERY("Reboot to recovery", "recovery"),
    BOOTLOADER("Reboot to bootloader", "bootloader"),
}

enum class PackageFilter(val flag: String?) { ALL(null), USER("-3"), SYSTEM("-s"), DISABLED("-d") }

/**
 * Builds every System Toolbox command from validated parts. Nothing here
 * runs anything; a builder throws [IllegalArgumentException] for input that
 * could smuggle extra shell syntax (bad package names, relative paths, …).
 */
object ToolboxCommands {
    private val SETTING_KEY = Regex("^[A-Za-z0-9_.:-]{1,128}$")
    private val PERMISSION = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
    private val LOG_TAG = Regex("^[A-Za-z0-9_.:*-]{1,64}$")
    private val LOG_LEVELS = setOf("V", "D", "I", "W", "E", "F", "S")

    // ----- read-only -----
    fun listPackages(filter: PackageFilter = PackageFilter.ALL): ToolboxCommand =
        ToolboxCommand("List apps", listOfNotNull("pm", "list", "packages", filter.flag), changesSystem = false)

    fun packageInfo(pkg: String) = ToolboxCommand("App details", listOf("dumpsys", "package", pkgName(pkg)), changesSystem = false)

    fun apkPath(pkg: String) = ToolboxCommand("APK path", listOf("pm", "path", pkgName(pkg)), changesSystem = false)

    fun logcat(tag: String? = null, level: String = "V", lines: Int = 500): ToolboxCommand {
        require(level in LOG_LEVELS) { "Unknown log level" }
        require(lines in 1..20_000) { "Lines must be 1..20000" }
        val filter = if (tag.isNullOrBlank()) {
            listOf("*:$level")
        } else {
            require(LOG_TAG.matches(tag)) { "Tag has characters logcat does not accept" }
            listOf("$tag:$level", "*:S")
        }
        return ToolboxCommand("Logcat", listOf("logcat", "-d", "-v", "time", "-t", lines.toString()) + filter, changesSystem = false)
    }

    fun settingsGet(ns: SettingsNamespace, key: String) =
        ToolboxCommand("Read setting", listOf("settings", "get", ns.wire, settingKey(key)), changesSystem = false)

    fun settingsList(ns: SettingsNamespace) = ToolboxCommand("List settings", listOf("settings", "list", ns.wire), changesSystem = false)

    fun getprop() = ToolboxCommand("System properties", listOf("getprop"), changesSystem = false)
    fun battery() = ToolboxCommand("Battery", listOf("dumpsys", "battery"), changesSystem = false)
    fun storage() = ToolboxCommand("Storage", listOf("df", "-h"), changesSystem = false)

    fun listDir(path: String, root: Boolean = true) =
        ToolboxCommand("List folder", listOf("ls", "-la", absPath(path)), changesSystem = false, needsRoot = root)

    fun readFile(path: String, root: Boolean = true) =
        ToolboxCommand("Read file", listOf("head", "-c", "65536", absPath(path)), changesSystem = false, needsRoot = root)

    // ----- system-changing -----
    fun install(apkPath: String) = ToolboxCommand("Install APK", listOf("pm", "install", "-r", absPath(apkPath).also { require(it.endsWith(".apk")) { "Pick an .apk file" } }), changesSystem = true)
    fun uninstall(pkg: String) = ToolboxCommand("Uninstall", listOf("pm", "uninstall", "--user", "0", pkgName(pkg)), changesSystem = true)
    fun disable(pkg: String) = ToolboxCommand("Freeze (disable)", listOf("pm", "disable-user", "--user", "0", pkgName(pkg)), changesSystem = true)
    fun enable(pkg: String) = ToolboxCommand("Enable", listOf("pm", "enable", "--user", "0", pkgName(pkg)), changesSystem = true)
    fun clearData(pkg: String) = ToolboxCommand("Clear data", listOf("pm", "clear", "--user", "0", pkgName(pkg)), changesSystem = true)
    fun forceStop(pkg: String) = ToolboxCommand("Force stop", listOf("am", "force-stop", pkgName(pkg)), changesSystem = true)

    fun grant(pkg: String, permission: String) =
        ToolboxCommand("Grant permission", listOf("pm", "grant", pkgName(pkg), perm(permission)), changesSystem = true)

    fun revoke(pkg: String, permission: String) =
        ToolboxCommand("Revoke permission", listOf("pm", "revoke", pkgName(pkg), perm(permission)), changesSystem = true)

    fun settingsPut(ns: SettingsNamespace, key: String, value: String): ToolboxCommand {
        require(value.length <= 1024 && '\n' !in value && '\u0000' !in value) { "Value must be one line" }
        return ToolboxCommand("Change setting", listOf("settings", "put", ns.wire, settingKey(key), value), changesSystem = true)
    }

    fun reboot(target: RebootTarget) = ToolboxCommand(target.label, listOfNotNull("reboot", target.arg), changesSystem = true)

    /** Copies an app's base APK to [destDir] (an absolute folder, e.g. /sdcard/Download/apk-backup). */
    fun backupApk(pkg: String, sourceApk: String, destDir: String): ToolboxCommand {
        val p = pkgName(pkg)
        val src = absPath(sourceApk)
        require(src.endsWith(".apk")) { "Source must be an .apk path from pm path" }
        val dest = absPath(destDir).trimEnd('/')
        return ToolboxCommand("Back up APK", listOf("cp", src, "$dest/$p.apk"), changesSystem = true)
    }

    /** Free-form terminal input. Always treated as system-changing, so it always asks first. */
    fun terminal(line: String): ToolboxCommand {
        require(line.isNotBlank()) { "Type a command" }
        require('\u0000' !in line) { "NUL is not allowed" }
        return ToolboxCommand("Terminal", listOf("sh", "-c", line), changesSystem = true)
    }

    /** Parses `pm path` output (`package:/data/app/.../base.apk`) into paths. */
    fun parseApkPaths(output: String): List<String> =
        output.lines().map { it.trim() }.filter { it.startsWith("package:") }.map { it.removePrefix("package:") }

    /** Parses `pm list packages` output into sorted package names. */
    fun parsePackages(output: String): List<String> =
        output.lines().map { it.trim() }.filter { it.startsWith("package:") }.map { it.removePrefix("package:") }
            .filter { PrivilegedNames.isSafeAndroidName(it) }.sorted()

    /** Pulls `android.permission.X: granted=true` rows out of `dumpsys package`. */
    fun parsePermissions(dumpsys: String): List<Pair<String, Boolean>> {
        val row = Regex("^\\s*([A-Za-z0-9_.]+): granted=(true|false)")
        return dumpsys.lines().mapNotNull { line -> row.find(line)?.let { it.groupValues[1] to (it.groupValues[2] == "true") } }
            .distinctBy { it.first }.sortedBy { it.first }
    }

    /** Filters captured logcat text the way the viewer's search box does. */
    fun filterLog(log: String, query: String): String =
        if (query.isBlank()) log else log.lines().filter { it.contains(query, ignoreCase = true) }.joinToString("\n")

    private fun pkgName(pkg: String): String = pkg.trim().also { require(PrivilegedNames.isSafeAndroidName(it)) { "Not a valid package name" } }
    private fun perm(p: String): String = p.trim().also { require(PERMISSION.matches(it) && it.length <= 256) { "Not a valid permission name" } }
    private fun settingKey(k: String): String = k.trim().also { require(SETTING_KEY.matches(it)) { "Not a valid settings key" } }

    private fun absPath(path: String): String {
        val p = path.trim()
        require(p.startsWith("/")) { "Use an absolute path" }
        require(p.length <= 1024 && '\n' !in p && '\u0000' !in p) { "Bad path" }
        require(p.split('/').none { it == ".." }) { "'..' is not allowed" }
        return p
    }
}
