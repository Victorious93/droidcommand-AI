package ai.droidcommand.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** JSON types a [ToolParameterSchema] can require. */
enum class ToolJsonType {
    STRING,
    NUMBER,
    BOOLEAN,
    ARRAY,
    OBJECT,
}

data class ToolParameter(
    val name: String,
    val type: ToolJsonType,
    val description: String = "",
    val required: Boolean = false,
) {
    init {
        require(name.isNotBlank()) { "parameter name must not be blank" }
    }
}

sealed class ToolSchemaValidation {
    data object Valid : ToolSchemaValidation()

    data class Invalid(val reason: String) : ToolSchemaValidation()
}

/**
 * Opt-in JSON Schema subset for a tool's arguments. Default
 * [additionalProperties] is true so an existing untyped tool that later
 * grows a partial schema does not start rejecting unknown keys.
 */
data class ToolParameterSchema(
    val parameters: List<ToolParameter> = emptyList(),
    val additionalProperties: Boolean = true,
) {
    init {
        val names = parameters.map { it.name }
        require(names.size == names.toSet().size) {
            "duplicate parameter name in schema: ${names.groupingBy { it }.eachCount().filter { it.value > 1 }.keys}"
        }
    }

    fun validate(arguments: Map<String, ToolArgumentValue>): ToolSchemaValidation {
        val byName = parameters.associateBy { it.name }
        for (param in parameters) {
            if (param.required && param.name !in arguments) {
                return ToolSchemaValidation.Invalid("missing required argument '${param.name}'")
            }
        }
        for ((name, value) in arguments) {
            val param = byName[name]
            if (param == null) {
                if (!additionalProperties) {
                    return ToolSchemaValidation.Invalid("unexpected argument '$name'")
                }
                continue
            }
            if (!matches(param.type, value)) {
                return ToolSchemaValidation.Invalid(
                    "argument '$name' must be ${param.type.name.lowercase()}",
                )
            }
        }
        return ToolSchemaValidation.Valid
    }

    private fun matches(type: ToolJsonType, value: ToolArgumentValue): Boolean = when (type) {
        ToolJsonType.STRING -> value is ToolArgumentValue.Text
        ToolJsonType.NUMBER -> value is ToolArgumentValue.Number
        ToolJsonType.BOOLEAN -> value is ToolArgumentValue.Bool
        ToolJsonType.ARRAY -> nestedIs(value, expectArray = true)
        ToolJsonType.OBJECT -> nestedIs(value, expectArray = false)
    }

    private fun nestedIs(value: ToolArgumentValue, expectArray: Boolean): Boolean {
        val json = (value as? ToolArgumentValue.NestedJson)?.json ?: return false
        val parsed = runCatching { JSON.parseToJsonElement(json) }.getOrNull() ?: return false
        return if (expectArray) parsed is JsonArray else parsed is JsonObject
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}
