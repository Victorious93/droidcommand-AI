package ai.droidcommand.agent

/** CAP-005, P0.5. */
enum class PersonaCategory { PERSONAL, PROFESSIONAL, CODING, CUSTOM }

/**
 * How formal a persona's writing is — a real, distinct enum (unlike
 * [StyleProfile.tone]/[StyleProfile.responseStructure], which the roadmap
 * prompt itself types as plain [String]): the spec treats [Formality] and
 * [Verbosity] as named types separate from its free-text style fields, a
 * real signal this codebase follows rather than invents. A first,
 * reasonable-but-arbitrary five-point scale, the same honesty
 * [ComplexityThresholds]' own doc comment already applies to its own
 * defaults — not a proven-correct calibration.
 */
enum class Formality { VERY_CASUAL, CASUAL, NEUTRAL, FORMAL, VERY_FORMAL }

/** How much a persona tends to say — see [Formality]'s doc comment for the same scale-honesty caveat. */
enum class Verbosity { CONCISE, MODERATE, DETAILED, VERBOSE }

/**
 * [VocabProfile]/[StructureProfile]/[HumorProfile] are, like
 * [StyleProfile.tone], referenced by the roadmap prompt but never given a
 * shape (the same "referenced but never defined" gap `AllocatedContext`/
 * `ProviderPreferences`/`AiProvider` already hit in this codebase) —
 * designed here as small, descriptive-`String`-field data classes,
 * consistent with the spec's own `String`-typed sibling fields, rather than
 * inventing rigid enums the spec never asked for.
 */
data class VocabProfile(val complexity: String, val notableWords: List<String> = emptyList())

data class StructureProfile(val typicalSentenceLength: String, val punctuationHabits: String)

data class HumorProfile(val present: Boolean, val style: String? = null)

/** A compact description of *how* someone writes — never *what* they said. CAP-005, P0.5's own required shape. */
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

/**
 * A saved communication-style profile (CAP-005, P0.5). [contextContribution]
 * is the compact, LLM-ready representation — real style guidance, never a
 * copy of the source conversations. [sourceConversations] holds the
 * conversation ids [ai.droidcommand.llm.DefaultPersonaManager] actually
 * analyzed to produce this persona, not necessarily every id a caller
 * originally requested (some may not have resolved via [ConversationStore]).
 *
 * **Critical Isolation (the roadmap prompt's own explicit requirement):**
 * a [Persona] is pure communication-style data. Nothing in `core-agent` or
 * `core-llm` ever reads a [Persona] field to make a security, permission,
 * authorization, or execution-policy decision — `core-security`/`core-root`
 * depend on neither `core-llm` nor this type, so there is no code path from
 * [contextContribution] to any policy decision by construction, not by an
 * added runtime check. The only place [contextContribution] is ever used is
 * as an [ai.droidcommand.llm.LlmRequest.systemPrompt]-shaped value.
 */
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
 * Declared for spec completeness (P0.5) — not yet threaded through any
 * signature in this codebase. This slice's persona-creation flow
 * ([ai.droidcommand.llm.DefaultPersonaManager.createPersonaFromConversations])
 * is synchronous end-to-end (one LLM call that either succeeds or fails),
 * with no staged/background pipeline that would have a real interim status
 * to report. Real future use once persona creation becomes asynchronous.
 */
enum class PersonaActivationStatus { UPLOADED, PARSED, ANALYZED, PROFILE_CREATED, ACTIVATED, RUNTIME_TESTED }

/** Discovery + lookup for saved [Persona]s — the same shape [KnowledgeStore]/[ConversationStore] already establish. [Persona] is immutable, so no snapshot discipline is needed. */
interface PersonaStore {
    fun save(persona: Persona)

    fun load(id: String): Persona?

    fun list(): List<String>

    fun delete(id: String): Boolean
}

/** A real, immediately usable [PersonaStore] — but, like [InMemoryKnowledgeStore], it does not survive a process restart. [JsonFilePersonaStore] is the persistent alternative. */
class InMemoryPersonaStore : PersonaStore {
    private val personas = mutableMapOf<String, Persona>()
    private val lock = Any()

    override fun save(persona: Persona) {
        synchronized(lock) { personas[persona.id] = persona }
    }

    override fun load(id: String): Persona? = synchronized(lock) { personas[id] }

    override fun list(): List<String> = synchronized(lock) { personas.keys.toList().sorted() }

    override fun delete(id: String): Boolean = synchronized(lock) { personas.remove(id) != null }
}
