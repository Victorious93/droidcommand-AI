package ai.droidcommand.bots

/**
 * Bot Maker, Phase B1 (`docs/BRAIN_JARVIS_BOTMAKER_PLAN.md` §3) — a saved
 * recipe for a small, named bot: a persona plus a restricted set of tools
 * plus a memory folder, run manually. [personaId] references an existing
 * `ai.droidcommand.agent.Persona` (looked up via `PersonaStore`) rather than
 * duplicating a soul string, per the plan's explicit "not a second soul
 * string" requirement.
 *
 * [providerType] is kept as a plain opaque string (e.g. a BYOK provider key
 * or a local model id) so this module never depends on `core-llm-factory` —
 * `core-bots` only produces the pieces ([ScopedToolRunner], the composed
 * system prompt via [BotRunner]) that an existing `ChatSession` /
 * `DroidCommandSession` call site already knows how to consume.
 *
 * [allowedTools] is the sandbox: an empty set means chat-only, per the
 * plan's "Empty means chat only" rule. [memoryFolder] is a path *inside*
 * the existing Brain vault (`core-notes.NoteVault`), not a new storage
 * primitive — this module does not depend on `core-notes` either; the
 * caller is responsible for scoping vault IO to that folder, the same way
 * [ScopedToolRunner] scopes tool calls.
 *
 * Trigger (manual/phrase/time) is deliberately not modeled here — B1 is
 * manual-run only. B3 (phrase) and B4 (time) are future phases per the
 * plan's own table and are not fields on this type.
 */
data class BotRecipe(
    val id: String,
    val name: String,
    val personaId: String,
    val instructions: String = "",
    val providerType: String,
    val allowedTools: Set<String> = emptySet(),
    val memoryFolder: String,
    val enabled: Boolean = true,
)
