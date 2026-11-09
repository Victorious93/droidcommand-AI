package ai.droidcommand.skills

/** The outcome of binding a skill's declared [SkillParameter]s to supplied values. */
sealed class SkillBindResult {
    /** Every required parameter was satisfied; [instructions] is the body with `{{name}}` slots filled. */
    data class Bound(val instructions: String) : SkillBindResult()

    /**
     * One or more required parameters had neither a supplied value nor a default, so the skill cannot be
     * safely bound. [missing] lists their names (in declaration order). A caller surfaces this (e.g. skips
     * the skill and tells the user which values it needs) rather than silently substituting a blank.
     */
    data class MissingRequired(val missing: List<String>) : SkillBindResult()
}

/**
 * Fills a skill's declared parameters into its instruction body — the piece that turns a parameterised
 * `SKILL.md` into the concrete text a planner sees. Pure and dependency-free (a [SkillManifest] plus a
 * value lookup → a [SkillBindResult]), so `core-skills` stays decoupled; a caller (e.g. the `cli`
 * composition root) supplies values from wherever its policy says — config, a values file, a UI — and
 * places the bound instructions where the prompt is assembled.
 *
 * Substitution is a single left-to-right pass over the body, replacing each `{{name}}` (optional inner
 * whitespace allowed, as in `{{ name }}`) whose `name` is a declared parameter with that parameter's
 * resolved value. It is deliberately single-pass: a value that happens to contain `{{other}}` is never
 * re-expanded, so the result cannot depend on parameter order or loop into itself. A `{{...}}` whose
 * name is not a declared parameter is left exactly as written — it may be literal braces in the body,
 * and inventing a substitution (or erroring) would be wrong.
 *
 * Value resolution for each declared parameter, in order:
 *  1. the supplied value (`values(name)`), if non-null;
 *  2. otherwise the parameter's [SkillParameter.default], if any;
 *  3. otherwise, if the parameter is [SkillParameter.required], it is *missing* — the whole bind fails
 *     with [SkillBindResult.MissingRequired] and no substitution happens;
 *  4. otherwise (optional, no value, no default) it resolves to the empty string.
 *
 * Binding substitutes text only; it grants the skill nothing, exactly like the rest of `core-skills`.
 */
class SkillParameterBinder {
    fun bind(manifest: SkillManifest, values: (String) -> String?): SkillBindResult {
        val resolved = LinkedHashMap<String, String>()
        val missing = mutableListOf<String>()
        for (parameter in manifest.parameters) {
            val value = values(parameter.name) ?: parameter.default
            when {
                value != null -> resolved[parameter.name] = value
                parameter.required -> missing += parameter.name
                else -> resolved[parameter.name] = ""
            }
        }
        if (missing.isNotEmpty()) {
            return SkillBindResult.MissingRequired(missing)
        }

        val bound = PLACEHOLDER.replace(manifest.instructions) { match ->
            // Leave an unknown placeholder untouched (replacement expects the literal match back).
            resolved[match.groupValues[1]] ?: match.value
        }
        return SkillBindResult.Bound(bound)
    }

    private companion object {
        /** `{{name}}` with optional inner whitespace; `name` is a skill-parameter slug. */
        val PLACEHOLDER = Regex("""\{\{\s*([A-Za-z][A-Za-z0-9_.-]*)\s*}}""")
    }
}
