package ai.droidcommand.templates

enum class TemplateCategory { CODING, WRITING, ANALYSIS, CREATIVE, CUSTOM }

/**
 * A named, reusable prompt with `{{variable}}` slots. Variable names are
 * letters, digits and `_`, not starting with a digit; whitespace inside the
 * braces is ignored (`{{ topic }}`). [builtIn] templates ship with the app;
 * user-created ones are [builtIn] = false.
 */
data class PromptTemplate(
    val id: String,
    val name: String,
    val category: TemplateCategory,
    val body: String,
    val builtIn: Boolean = false,
) {
    init {
        require(id.isNotBlank()) { "id must not be blank" }
        require(name.isNotBlank()) { "name must not be blank" }
    }

    /** Distinct slot names in order of first appearance. */
    val variables: List<String> get() = TemplateEngine.variablesOf(body)
}

sealed class TemplateResult {
    data class Expanded(val text: String) : TemplateResult()

    /** Every slot in the template that [TemplateEngine.expand] had no value for. */
    data class MissingVariables(val names: List<String>) : TemplateResult()
}

object TemplateEngine {
    private val SLOT = Regex("""\{\{\s*([A-Za-z_][A-Za-z0-9_]*)\s*}}""")

    fun variablesOf(body: String): List<String> = SLOT.findAll(body).map { it.groupValues[1] }.distinct().toList()

    /**
     * Single-pass substitution: a supplied value is inserted literally and
     * is never itself scanned for slots, so a value containing `{{other}}`
     * cannot pull in another variable. A blank value counts as missing,
     * because an empty slot silently produces a malformed prompt. Extra
     * values with no matching slot are ignored.
     */
    fun expand(template: PromptTemplate, values: Map<String, String>): TemplateResult {
        val missing = template.variables.filter { values[it].isNullOrBlank() }
        if (missing.isNotEmpty()) return TemplateResult.MissingVariables(missing)
        return TemplateResult.Expanded(SLOT.replace(template.body) { values.getValue(it.groupValues[1]) })
    }
}
