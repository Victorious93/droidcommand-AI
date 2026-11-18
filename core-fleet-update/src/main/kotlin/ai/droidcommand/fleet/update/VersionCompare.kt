package ai.droidcommand.fleet.update

/**
 * Compares release tags / `versionName`s such as `v0.1.8`, `0.2.0-debug`, `1.4`. Numeric dot-separated parts are
 * compared as numbers (so 0.1.10 > 0.1.9); a missing part counts as 0; anything after the first `-` or `+`
 * is ignored. A name with no leading digits is not comparable and returns null — callers must then not
 * claim an update exists.
 */
object VersionCompare {
    fun parse(version: String?): List<Int>? {
        val core = version?.trim()?.removePrefix("v")?.removePrefix("V")?.substringBefore('-')?.substringBefore('+')
            ?: return null
        val parts = core.split('.')
        if (parts.isEmpty() || parts.any { it.isEmpty() || !it.all(Char::isDigit) || it.length > 9 }) return null
        return parts.map { it.toInt() }
    }

    /** Negative if [a] is older than [b], 0 if equal, positive if newer; null if either is not comparable. */
    fun compare(a: String?, b: String?): Int? {
        val x = parse(a) ?: return null
        val y = parse(b) ?: return null
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }
}
