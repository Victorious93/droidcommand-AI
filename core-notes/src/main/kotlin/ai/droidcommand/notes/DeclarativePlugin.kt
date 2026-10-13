package ai.droidcommand.notes

/**
 * A user-authored plugin made only of literal text operations. No code runs. `|` separates the
 * operation from its text, so a value cannot contain `|`.
 *
 * File format (`.dcplugin`), one field per line, `#` comments allowed:
 * ```
 * id: my-plugin
 * name: My plugin
 * description: What it does
 * enabled: true
 * op: prepend|> quoted
 * op: append|\n
 * op: replace|TODO|DONE
 * ```
 */
data class DeclarativePlugin(
    override val id: String,
    override val displayName: String,
    override val description: String,
    val ops: List<PluginOp>,
    val enabled: Boolean = true,
) : NotePlugin {
    override fun transform(markdown: String): String {
        var text = markdown
        for (op in ops) {
            text = when (op) {
                is PluginOp.Prepend -> op.text + text
                is PluginOp.Append -> text + op.text
                is PluginOp.Replace -> text.replace(op.from, op.to)
            }
            if (text.length > MAX_OUTPUT) text = text.take(MAX_OUTPUT)
        }
        return text
    }

    fun encode(): String = buildString {
        appendLine("id: $id")
        appendLine("name: $displayName")
        appendLine("description: $description")
        appendLine("enabled: $enabled")
        ops.forEach { op ->
            appendLine(
                when (op) {
                    is PluginOp.Prepend -> "op: prepend|${op.text}"
                    is PluginOp.Append -> "op: append|${op.text}"
                    is PluginOp.Replace -> "op: replace|${op.from}|${op.to}"
                },
            )
        }
    }

    fun toInstalled(): InstalledPlugin = InstalledPlugin(id, displayName, description, enabled, ops.size)

    private companion object {
        const val MAX_OUTPUT = 256_000
    }
}

sealed class PluginOp {
    data class Prepend(val text: String) : PluginOp()
    data class Append(val text: String) : PluginOp()
    data class Replace(val from: String, val to: String) : PluginOp()
}

fun parsePluginSource(source: String): DeclarativePlugin {
    require(source.length <= MAX_SOURCE) { "Plugin file is too large." }
    var id: String? = null
    var name: String? = null
    var description = ""
    var enabled = true
    val ops = mutableListOf<PluginOp>()
    source.lineSequence().forEach { raw ->
        // Do not trim the end: an operation's text may intentionally end with a space.
        val line = raw.trimStart()
        if (line.isBlank() || line.startsWith("#")) return@forEach
        val separator = line.indexOf(':')
        require(separator > 0) { "Expected key: value, got: $line" }
        val key = line.substring(0, separator).trim().lowercase()
        // Operation text is kept as written (a leading space after ':' is dropped, a trailing space is not).
        val value = if (key == "op") line.substring(separator + 1).trimStart() else line.substring(separator + 1).trim()
        when (key) {
            "id" -> id = requirePluginId(value)
            "name" -> {
                require(value.isNotEmpty() && value.length <= 60) { "Plugin name must be 1–60 characters." }
                name = value
            }
            "description" -> {
                require(value.length <= 240) { "Plugin description is too long." }
                description = value
            }
            "enabled" -> enabled = when (value.lowercase()) {
                "true" -> true
                "false" -> false
                else -> throw IllegalArgumentException("enabled must be true or false.")
            }
            "op" -> {
                require(ops.size < MAX_OPS) { "A plugin can declare at most $MAX_OPS operations." }
                ops += parseOp(value)
            }
            else -> throw IllegalArgumentException("Unknown plugin field: $key")
        }
    }
    return DeclarativePlugin(
        id = id ?: throw IllegalArgumentException("Plugin needs an id."),
        displayName = name ?: throw IllegalArgumentException("Plugin needs a name."),
        description = description,
        ops = ops,
        enabled = enabled,
    )
}

private fun parseOp(value: String): PluginOp {
    val kind = value.substringBefore('|').trim().lowercase()
    val rest = value.substringAfter('|', "")
    return when (kind) {
        "prepend" -> PluginOp.Prepend(boundedText(unescape(rest), "prepend text"))
        "append" -> PluginOp.Append(boundedText(unescape(rest), "append text"))
        "replace" -> {
            val parts = rest.split('|', limit = 2)
            require(parts.size == 2) { "replace needs from|to." }
            val from = boundedText(unescape(parts[0]), "replace text")
            require(from.isNotEmpty()) { "replace needs a non-empty from text." }
            PluginOp.Replace(from, boundedText(unescape(parts[1]), "replace text"))
        }
        else -> throw IllegalArgumentException("Unknown operation: $kind. Use prepend, append or replace.")
    }
}

private fun unescape(text: String): String = text.replace("\\n", "\n").replace("\\t", "\t")

private fun boundedText(text: String, label: String): String {
    require(text.length <= MAX_TEXT) { "$label is too long (max $MAX_TEXT)." }
    return text
}

private fun requirePluginId(value: String): String {
    require(ID.matches(value)) { "Plugin id must be 1–40 characters of lowercase letters, digits or hyphens." }
    require(!value.startsWith("-") && !value.endsWith("-")) { "Plugin id cannot start or end with a hyphen." }
    return value
}

private val ID = Regex("^[a-z0-9-]{1,40}$")
private const val MAX_SOURCE = 8_000
private const val MAX_OPS = 20
private const val MAX_TEXT = 500
