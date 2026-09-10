package ai.droidcommand.agent

/** The four categories `docs/CAPABILITY_ROADMAP_PROMPT.md`'s P0.5 comment names — no unnamed "etc." category guessed at. */
enum class PersonaCategory { PERSONAL, PROFESSIONAL, CODING, CUSTOM }

/**
 * A coarse, fixed-vocabulary formality scale. The spec types [Persona]'s
 * `formality` as its own field distinct from the free-text `tone`,
 * implying a categorical value — a fixed vocabulary is also far more
 * reliable to extract via JSON prompting than open text on what's meant
 * to be a scale.
 */
enum class Formality { VERY_CASUAL, CASUAL, NEUTRAL, FORMAL, VERY_FORMAL }

/** A coarse, fixed-vocabulary verbosity scale — see [Formality]'s own doc for why this is an enum, not free text. */
enum class Verbosity { TERSE, CONCISE, MODERATE, DETAILED, VERBOSE }

/**
 * "Vocabulary & Language patterns" (P0.5's own extraction bullet list).
 * Undefined in the roadmap prompt — designed around that bullet, as
 * free-text/list fields a real extractor populates with its own judgment,
 * not a fabricated fixed taxonomy.
 */
data class VocabProfile(
    val complexityLevel: String,
    val notableVocabulary: List<String> = emptyList(),
    val jargonDomains: List<String> = emptyList(),
)

/** "Sentence structure & rhythm." Undefined in the roadmap prompt — designed the same way as [VocabProfile]. */
data class StructureProfile(
    val typicalSentenceLength: String,
    val rhythmDescription: String,
    val punctuationHabits: List<String> = emptyList(),
)

/** "Humor patterns & wit." Undefined in the roadmap prompt — designed the same way as [VocabProfile]. */
data class HumorProfile(
    val present: Boolean,
    val style: String? = null,
    val examples: List<String> = emptyList(),
)

/** Verbatim the roadmap prompt's 8 [StyleProfile] fields. */
data class StyleProfile(
    val tone: String,
    val vocabulary: VocabProfile,
    val sentenceStructure: StructureProfile,
    val formality: Formality,
    val verbosity: Verbosity,
    val humor: HumorProfile,
    val responseStructure: String,
    val commonExpressions: List<String> = emptyList(),
)

/** Verbatim the roadmap prompt's 8 [Persona] fields. */
data class Persona(
    val id: String,
    val name: String,
    val category: PersonaCategory,
    val sourceConversations: List<String>,
    val styleCharacteristics: StyleProfile,
    val contextContribution: String,
    val version: String,
    val enabled: Boolean,
)

/**
 * A compact, deterministic, mechanical rendering of [profile] — **never
 * re-touches the source conversation** — for use as [Persona.contextContribution].
 * Directly implements P0.5's own "do not simply copy the uploaded
 * conversation into every prompt; create a compact, reusable
 * representation," mirroring [formatKnowledgeContext]'s exact precedent
 * of turning structured data into one block of prompt-ready text.
 */
fun formatStyleProfile(profile: StyleProfile): String {
    val vocabulary = buildString {
        append("Vocabulary: ${profile.vocabulary.complexityLevel}")
        if (profile.vocabulary.notableVocabulary.isNotEmpty()) {
            append(", notable: ${profile.vocabulary.notableVocabulary.joinToString(", ")}")
        }
        if (profile.vocabulary.jargonDomains.isNotEmpty()) {
            append(", domains: ${profile.vocabulary.jargonDomains.joinToString(", ")}")
        }
    }
    val structure = buildString {
        append("Sentence style: ${profile.sentenceStructure.typicalSentenceLength}, ${profile.sentenceStructure.rhythmDescription}")
        if (profile.sentenceStructure.punctuationHabits.isNotEmpty()) {
            append("; punctuation: ${profile.sentenceStructure.punctuationHabits.joinToString(", ")}")
        }
    }
    val humor = if (profile.humor.present) {
        buildString {
            append("Humor: ${profile.humor.style ?: "present"}")
            if (profile.humor.examples.isNotEmpty()) append(" (e.g. ${profile.humor.examples.joinToString(", ")})")
        }
    } else {
        "Humor: none"
    }
    val expressions = if (profile.commonExpressions.isEmpty()) {
        null
    } else {
        "Common expressions: ${profile.commonExpressions.joinToString(", ")}"
    }

    return listOfNotNull(
        "Tone: ${profile.tone}",
        "Formality: ${profile.formality}",
        "Verbosity: ${profile.verbosity}",
        vocabulary,
        structure,
        humor,
        "Response structure: ${profile.responseStructure}",
        expressions,
    ).joinToString(". ")
}
