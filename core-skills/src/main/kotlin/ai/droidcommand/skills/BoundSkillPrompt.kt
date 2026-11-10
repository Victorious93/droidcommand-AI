package ai.droidcommand.skills

/** A skill that was enabled but could not be bound because required parameter values were missing. */
data class SkippedSkill(val name: String, val missing: List<String>)

/**
 * The system-prompt contribution of enabled skills after parameter binding, plus the skills that
 * were skipped so a caller can tell the user which values are still needed.
 */
data class BoundSkillPrompt(
    val prompt: String?,
    val skipped: List<SkippedSkill> = emptyList(),
) {
    fun skippedNotice(): String? {
        if (skipped.isEmpty()) return null
        return skipped.joinToString(" ") { skill ->
            "Skill '${skill.name}' was skipped: missing required settings (${skill.missing.joinToString(", ")})."
        }
    }
}

/**
 * Binds each **enabled** skill's parameters, then assembles the ones that bound cleanly.
 * A required parameter with neither a supplied value nor a default skips that skill
 * (it is listed in [BoundSkillPrompt.skipped]) instead of contributing a half-filled prompt
 * or throwing. Disabled skills are ignored and never reported as skipped.
 */
fun assembleBoundSkills(
    skills: List<InstalledSkill>,
    binder: SkillParameterBinder = SkillParameterBinder(),
    assembler: SkillPromptAssembler = SkillPromptAssembler(),
    values: (skillName: String, parameterName: String) -> String?,
): BoundSkillPrompt {
    val skipped = mutableListOf<SkippedSkill>()
    val contributing = skills.mapNotNull { skill ->
        if (!skill.enabled) return@mapNotNull null
        val name = skill.manifest.name
        when (val bound = binder.bind(skill.manifest) { param -> values(name, param) }) {
            is SkillBindResult.Bound ->
                skill.copy(manifest = skill.manifest.copy(instructions = bound.instructions))
            is SkillBindResult.MissingRequired -> {
                skipped += SkippedSkill(name, bound.missing)
                null
            }
        }
    }
    return BoundSkillPrompt(prompt = assembler.assemble(contributing), skipped = skipped)
}
