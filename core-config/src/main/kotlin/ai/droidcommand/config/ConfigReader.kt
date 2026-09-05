package ai.droidcommand.config

class MissingConfigException(key: String) : IllegalStateException("Missing required configuration: $key")

/** Typed accessors over a [ConfigSource]. Never caches a value — every call re-reads the source. */
class ConfigReader(private val source: ConfigSource) {
    fun require(key: String): String = source.get(key) ?: throw MissingConfigException(key)

    fun optional(key: String, default: String? = null): String? = source.get(key) ?: default

    fun optionalInt(key: String, default: Int? = null): Int? = source.get(key)?.toIntOrNull() ?: default

    fun optionalDouble(key: String, default: Double? = null): Double? = source.get(key)?.toDoubleOrNull() ?: default

    fun optionalBoolean(key: String, default: Boolean = false): Boolean =
        source.get(key)?.equals("true", ignoreCase = true) ?: default
}
