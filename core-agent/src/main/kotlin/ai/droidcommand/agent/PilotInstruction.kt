package ai.droidcommand.agent

/**
 * Parses one Pilot instruction in the same shape the CLI uses: a tool name, then `key=value`
 * arguments separated by whitespace. The first `=` in an argument splits the key from the value,
 * so a value may itself contain `=`.
 */
sealed class PilotParse {
    data class Ok(val toolName: String, val input: Map<String, String>) : PilotParse()

    data class Invalid(val message: String) : PilotParse()
}

object PilotInstruction {
    fun parse(text: String): PilotParse {
        val tokens = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) {
            return PilotParse.Invalid("Enter a tool name, then key=value arguments.")
        }
        val toolName = tokens.first()
        if ('=' in toolName) {
            return PilotParse.Invalid("The first word must be a tool name, not key=value.")
        }
        val input = linkedMapOf<String, String>()
        for (pair in tokens.drop(1)) {
            val separator = pair.indexOf('=')
            if (separator <= 0) {
                return PilotParse.Invalid("Invalid argument '$pair'. Use key=value.")
            }
            input[pair.substring(0, separator)] = pair.substring(separator + 1)
        }
        return PilotParse.Ok(toolName, input)
    }
}
