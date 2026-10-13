package ai.droidcommand.app.pilot

/**
 * Pilot input is one `key=value` pair per line, the same shape the CLI uses for a single tool call.
 * Blank lines and `#` comments are ignored. A value may contain spaces and further `=` signs.
 */
fun parsePilotInput(raw: String): Map<String, String> {
    val out = linkedMapOf<String, String>()
    raw.lineSequence().forEach { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
        val eq = trimmed.indexOf('=')
        require(eq > 0) { "Expected key=value, got: $trimmed" }
        val key = trimmed.substring(0, eq).trim()
        require(key.isNotEmpty()) { "Expected key=value, got: $trimmed" }
        out[key] = trimmed.substring(eq + 1)
    }
    return out
}
