package ai.droidcommand.tools.android

/** Parses a [Selector] from `Tool` input: `by` in {text, resourceId, contentDescription, className}, `value`, optional `exact` (default true). */
fun parseSelector(input: Map<String, String>): Result<Selector> {
    val by = input["by"] ?: return Result.failure(IllegalArgumentException("Missing required input 'by'"))
    val value = input["value"] ?: return Result.failure(IllegalArgumentException("Missing required input 'value'"))
    val exact = input["exact"]?.equals("true", ignoreCase = true) ?: true

    return when (by.lowercase()) {
        "text" -> Result.success(Selector.ByText(value, exact))
        "resourceid" -> Result.success(Selector.ByResourceId(value))
        "contentdescription" -> Result.success(Selector.ByContentDescription(value, exact))
        "classname" -> Result.success(Selector.ByClassName(value))
        else -> Result.failure(IllegalArgumentException("Unknown selector type '$by'; expected one of: text, resourceId, contentDescription, className"))
    }
}
