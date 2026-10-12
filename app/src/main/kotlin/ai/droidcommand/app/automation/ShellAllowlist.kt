package ai.droidcommand.app.automation

/**
 * The executables that shell keys may run, stored one per line in AdvancedPrefs. The list starts empty, so nothing runs
 * until the owner allows an executable. An entry must equal a key's executable exactly. Pure, so it is JVM-tested.
 */
object ShellAllowlist {
    fun parse(raw: String?): Set<String> =
        raw.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted().toSet()

    /** Drops any entry that would not survive the one-per-line format. */
    fun serialize(entries: Set<String>): String =
        entries
            .map { it.trim() }
            .filter { it.isNotEmpty() && '\n' !in it && '\r' !in it }
            .distinct()
            .sorted()
            .joinToString("\n")
}
