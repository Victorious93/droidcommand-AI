package ai.droidcommand.skills

/**
 * The parsed metadata of a packaged skill (a `SKILL.md` file): its frontmatter
 * plus the instruction body that follows it.
 *
 * [declaredTools] is exactly that — a *declaration* of which tools the skill's
 * instructions expect to use, read from frontmatter. It is advisory metadata,
 * **not** a grant: installing or enabling a skill (see [SkillRegistry]) never
 * gives the skill access to any tool. Actual tool access stays behind the
 * existing capability / policy / approval / grant stack, exactly as it is for a
 * human-driven request. Recording what a skill *says* it needs lets a caller
 * show the user that list before approving, and lets a policy layer cross-check
 * a skill's tool calls against what it declared — neither of which is the same
 * as handing it the tools.
 */
data class SkillManifest(
    val name: String,
    val description: String,
    val declaredTools: List<String> = emptyList(),
    val version: String? = null,
    val instructions: String = "",
    val parameters: List<SkillParameter> = emptyList(),
) {
    init {
        require(NAME_PATTERN.matches(name)) {
            "skill name must be a slug (letters, digits, '-', '_', '.'; starting with a letter), was '$name'"
        }
        require(description.isNotBlank()) { "skill description must not be blank" }
        require(parameters.map { it.name }.toSet().size == parameters.size) {
            "skill parameters must have unique names, were ${parameters.map { it.name }}"
        }
    }

    companion object {
        /** A conservative skill-name shape: a slug starting with a letter. Also keeps a name usable as a map key / file name. */
        val NAME_PATTERN = Regex("""[A-Za-z][A-Za-z0-9_.-]*""")
    }
}

/**
 * A single parameter a skill declares in its frontmatter — a named slot a caller
 * fills before the skill's instructions are used, substituted into the body
 * wherever `{{name}}` appears (see [SkillParameterBinder]).
 *
 * Declaring a parameter is metadata, not a capability: like [SkillManifest.declaredTools]
 * it only records what a skill *expects*, so a caller can collect values and a UI can
 * prompt for them. It grants the skill nothing. [required] with no supplied value and no
 * [default] means the skill cannot be bound (the binder reports it rather than silently
 * substituting a blank); an optional parameter with neither resolves to an empty string.
 */
data class SkillParameter(
    val name: String,
    val description: String = "",
    val required: Boolean = false,
    val default: String? = null,
) {
    init {
        require(SkillManifest.NAME_PATTERN.matches(name)) {
            "skill parameter name must be a slug (letters, digits, '-', '_', '.'; starting with a letter), was '$name'"
        }
    }
}
