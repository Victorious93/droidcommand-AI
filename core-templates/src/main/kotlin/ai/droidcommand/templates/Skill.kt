package ai.droidcommand.templates

import ai.droidcommand.llm.LlmRequest
import ai.droidcommand.llm.ProviderPreferences
import ai.droidcommand.llm.ProviderType

/**
 * A named, reusable system prompt with an optional provider hint.
 *
 * **Why this is not a `Persona` with two extra fields (the roadmap flagged
 * the question):** `Persona` requires an analyzed `StyleProfile` (tone,
 * vocabulary, formality, verbosity, humor, ...) derived from a user's own
 * conversations, and carries an explicit "pure communication-style data,
 * never read for any policy decision" isolation contract. A hand-written
 * skill like "Code Expert" has no analyzed style, and [preferredProviderType]
 * is a routing hint, which is exactly what `Persona`'s contract rules out.
 * The two share only the final step (becoming `LlmRequest.systemPrompt`),
 * which [SkillApplier] does without touching `Persona`. If the owner would
 * rather unify them later, the cost is a migration, not a rewrite.
 */
data class Skill(
    val id: String,
    val name: String,
    val description: String,
    val systemPrompt: String,
    val preferredProviderType: ProviderType? = null,
    val preferredModel: String? = null,
    val builtIn: Boolean = false,
) {
    init {
        require(id.isNotBlank()) { "id must not be blank" }
        require(name.isNotBlank()) { "name must not be blank" }
        require(systemPrompt.isNotBlank()) { "systemPrompt must not be blank" }
    }
}

object SkillApplier {
    /** Puts the skill's prompt ahead of any system prompt the request already has. */
    fun apply(skill: Skill, request: LlmRequest): LlmRequest {
        val combined = request.systemPrompt?.takeIf { it.isNotBlank() }
            ?.let { skill.systemPrompt + "\n\n" + it }
            ?: skill.systemPrompt
        return request.copy(systemPrompt = combined)
    }

    /**
     * Only a [ProviderType.LOCAL] preference is expressible today
     * (`requireLocal`). `ProviderPreferences` has no provider-type or
     * model field, so [Skill.preferredModel] and a CLOUD/SELF_HOSTED
     * preference are advisory data the caller may use, not enforced here.
     * An existing `requireLocal = true` is never relaxed.
     */
    fun preferencesFor(skill: Skill, base: ProviderPreferences = ProviderPreferences()): ProviderPreferences =
        if (skill.preferredProviderType == ProviderType.LOCAL) base.copy(requireLocal = true) else base
}
