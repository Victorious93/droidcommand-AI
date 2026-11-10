package ai.droidcommand.remotepc

/** Result of [RiskyCommandClassifier.classify]. [reasons] are plain words shown in the second confirm. */
data class CommandRisk(val risky: Boolean, val reasons: List<String>)

/**
 * Flags saved PC commands that can delete data, stop the PC, or change the whole system. It is a cautious
 * word check, not a shell parser: anything it is unsure about counts as risky. Every saved command is still
 * confirmed once; a risky one is confirmed twice.
 */
object RiskyCommandClassifier {
    /** First word of a command → why it is risky. */
    private val RISKY_PROGRAMS: Map<String, String> = buildMap {
        listOf("rm", "rmdir", "unlink", "shred", "srm", "wipe", "wipefs", "truncate").forEach { put(it, "deletes files") }
        listOf("reboot", "shutdown", "poweroff", "halt").forEach { put(it, "restarts or turns off the PC") }
        listOf("dd", "format", "fdisk", "sfdisk", "gdisk", "parted", "mkswap", "diskpart", "blkdiscard").forEach {
            put(it, "writes to a whole disk")
        }
        listOf("kill", "killall", "pkill", "xkill").forEach { put(it, "stops running programs") }
        listOf("userdel", "deluser", "passwd", "chpasswd", "usermod").forEach { put(it, "changes user accounts") }
        listOf("iptables", "ip6tables", "nft", "ufw", "firewall-cmd").forEach { put(it, "changes the firewall") }
        listOf("mv").forEach { put(it, "moves or overwrites files") }
        listOf("chmod", "chown", "chgrp").forEach { put(it, "changes file permissions") }
        listOf("crontab").forEach { put(it, "changes scheduled jobs") }
        listOf("sudo", "su", "doas", "pkexec").forEach { put(it, "runs as the admin (root)") }
        listOf("eval", "exec", "source", ".").forEach { put(it, "runs other hidden commands") }
    }

    /** Programs that are only risky with certain sub-commands. */
    private val RISKY_SUBCOMMANDS: Map<String, Map<String, String>> = mapOf(
        "systemctl" to listOf("reboot", "poweroff", "halt", "suspend", "hibernate", "kexec", "stop", "disable", "mask", "kill", "isolate")
            .associateWith { "stops services or the PC" },
        "init" to listOf("0", "6").associateWith { "restarts or turns off the PC" },
        "telinit" to listOf("0", "6").associateWith { "restarts or turns off the PC" },
        "git" to mapOf("clean" to "deletes files", "reset" to "throws away changes"),
        "docker" to mapOf("rm" to "deletes containers", "rmi" to "deletes images", "prune" to "deletes data", "kill" to "stops running programs"),
        "apt" to mapOf("remove" to "uninstalls software", "purge" to "uninstalls software", "autoremove" to "uninstalls software"),
        "apt-get" to mapOf("remove" to "uninstalls software", "purge" to "uninstalls software", "autoremove" to "uninstalls software"),
        "dnf" to mapOf("remove" to "uninstalls software", "erase" to "uninstalls software"),
        "pacman" to mapOf("-r" to "uninstalls software", "-rs" to "uninstalls software", "-rns" to "uninstalls software"),
        "snap" to mapOf("remove" to "uninstalls software"),
        "find" to mapOf("-delete" to "deletes files", "-exec" to "runs other commands on many files"),
        "zpool" to mapOf("destroy" to "deletes a disk pool"),
        "zfs" to mapOf("destroy" to "deletes data"),
        "lvremove" to emptyMap(),
    )

    private const val REASON_DISK = "writes to a whole disk"
    private const val REASON_OVERWRITE = "overwrites a file"
    private const val REASON_PIPE_SHELL = "downloads and runs a script"
    private const val REASON_FORK_BOMB = "can freeze the PC"
    private const val REASON_SUBSHELL = "runs other hidden commands"

    fun classify(command: String): CommandRisk {
        val reasons = linkedSetOf<String>()
        val text = command.trim()
        if (text.isEmpty()) return CommandRisk(false, emptyList())

        if (Regex(""":\s*\(\s*\)\s*\{""").containsMatchIn(text)) reasons += REASON_FORK_BOMB
        if (Regex("""(^|[^<])>\s*/dev/(sd|hd|nvme|mmcblk|vd|xvd|disk)""").containsMatchIn(text)) reasons += REASON_DISK
        val withoutSafeRedirects = text.replace(">>", " ").replace(Regex("""\d?>\s*/dev/null"""), " ")
        if (Regex("""(^|[\s;&|])>\s*[^\s>&|;]""").containsMatchIn(withoutSafeRedirects)) reasons += REASON_OVERWRITE
        if (Regex("""\|\s*(sudo\s+)?(ba|z|da|k)?sh\b""").containsMatchIn(text)) reasons += REASON_PIPE_SHELL
        if (text.contains("$(") || text.contains("`")) reasons += REASON_SUBSHELL

        segments(text).forEach { segment -> reasons += segmentReasons(segment) }
        return CommandRisk(reasons.isNotEmpty(), reasons.toList())
    }

    fun isRisky(command: String): Boolean = classify(command).risky

    /** Splits on `;`, `&&`, `||`, `|`, `&` and newlines so `ls; rm -rf x` is caught. */
    private fun segments(text: String): List<List<String>> =
        text.split(Regex("""\|\||&&|[;|&\n]"""))
            .map { tokens(it) }
            .filter { it.isNotEmpty() }

    private fun tokens(segment: String): List<String> =
        segment.trim().split(Regex("""\s+""")).map { it.trim('"', '\'', '(', ')', '{', '}') }.filter { it.isNotEmpty() }

    private fun segmentReasons(tokens: List<String>): List<String> {
        val reasons = mutableListOf<String>()
        var index = 0
        // Skip env assignments and wrappers so `FOO=1 nice rm x` and `/bin/rm x` are both seen as rm.
        while (index < tokens.size) {
            val word = tokens[index]
            when {
                Regex("^[A-Za-z_][A-Za-z0-9_]*=.*").matches(word) -> index++
                program(word) in WRAPPERS -> index++
                else -> break
            }
        }
        // Wrappers that are themselves risky (sudo) were skipped; flag them separately.
        tokens.take(index).map { program(it) }.forEach { RISKY_PROGRAMS[it]?.let(reasons::add) }
        if (index >= tokens.size) return reasons

        val name = program(tokens[index])
        RISKY_PROGRAMS[name]?.let(reasons::add)
        if (name.startsWith("mkfs") || name.startsWith("mke2fs")) reasons += REASON_DISK
        RISKY_SUBCOMMANDS[name]?.let { subs ->
            if (subs.isEmpty()) reasons += "deletes data"
            tokens.drop(index + 1).map { it.lowercase() }.firstNotNullOfOrNull { subs[it] }?.let(reasons::add)
        }
        return reasons
    }

    private val WRAPPERS = setOf("sudo", "doas", "nice", "nohup", "time", "env", "command", "builtin", "xargs", "timeout", "ionice", "stdbuf", "busybox")

    /** `/usr/bin/rm` → `rm`, `\rm` → `rm`, `RM` stays `rm`. */
    private fun program(word: String): String = word.substringAfterLast('/').trimStart('\\').lowercase()
}
