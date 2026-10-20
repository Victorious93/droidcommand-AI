package ai.droidcommand.remotecontrol

/**
 * Deterministic, LLM-free voice matching. Exact equality after normalize.
 * Empty / unmatched transcripts are ignored; two mappings or macros with the
 * same normalized phrase are ambiguous and never guessed.
 */
object VoicePhraseMatcher {
    fun normalize(raw: String): String =
        raw.lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    fun match(transcript: String, mappings: List<RemoteCommandMapping>): VoiceMatch {
        val needle = normalize(transcript)
        if (needle.isEmpty()) return VoiceMatch.None
        val hits = mappings.filter { it.normalizedVoicePhrase == needle }
        return when (hits.size) {
            0 -> VoiceMatch.None
            1 -> VoiceMatch.Hit(hits.single())
            else -> VoiceMatch.Ambiguous(hits.map { it.id })
        }
    }

    fun matchMacro(transcript: String, macros: List<Macro>): MacroVoiceMatch {
        val needle = normalize(transcript)
        if (needle.isEmpty()) return MacroVoiceMatch.None
        val hits = macros.filter {
            it.trigger.kind == MacroTriggerKind.VOICE && it.trigger.normalizedVoicePhrase == needle
        }
        return when (hits.size) {
            0 -> MacroVoiceMatch.None
            1 -> MacroVoiceMatch.Hit(hits.single())
            else -> MacroVoiceMatch.Ambiguous(hits.map { it.id })
        }
    }
}

sealed class VoiceMatch {
    data object None : VoiceMatch()
    data class Hit(val mapping: RemoteCommandMapping) : VoiceMatch()
    data class Ambiguous(val mappingIds: List<String>) : VoiceMatch()
}

sealed class MacroVoiceMatch {
    data object None : MacroVoiceMatch()
    data class Hit(val macro: Macro) : MacroVoiceMatch()
    data class Ambiguous(val macroIds: List<String>) : MacroVoiceMatch()
}
