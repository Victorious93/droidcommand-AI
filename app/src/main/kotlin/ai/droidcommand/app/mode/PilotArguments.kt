package ai.droidcommand.app.mode

/** Parses the Pilot argument field. Same `key=value` shape the `cli` `pilot` command already accepts. */
sealed class PilotArguments {
    data class Ok(val input: Map<String, String>) : PilotArguments()

    data class Invalid(val message: String) : PilotArguments()

    companion object {
        fun parse(text: String): PilotArguments {
            val input = linkedMapOf<String, String>()
            for (rawLine in text.lineSequence()) {
                val line = rawLine.trim()
                if (line.isEmpty()) continue
                val eq = line.indexOf('=')
                if (eq <= 0) return Invalid("Expected key=value, got '$line'.")
                val key = line.substring(0, eq).trim()
                if (key.isEmpty() || key.any { it.isWhitespace() }) return Invalid("Invalid key in '$line'.")
                input[key] = line.substring(eq + 1)
            }
            return Ok(input)
        }
    }
}
