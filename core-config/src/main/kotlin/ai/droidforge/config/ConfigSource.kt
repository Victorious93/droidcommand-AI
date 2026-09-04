package ai.droidforge.config

fun interface ConfigSource {
    fun get(key: String): String?
}

/**
 * Reads from process environment variables. [env] defaults to
 * `System::getenv` but is injectable so tests never touch real process
 * environment.
 */
class EnvConfigSource(private val env: (String) -> String? = System::getenv) : ConfigSource {
    override fun get(key: String): String? = env(key)
}

/** A fixed key/value map — the fixture used in tests, and usable for any non-secret default configuration. */
class MapConfigSource(private val values: Map<String, String>) : ConfigSource {
    override fun get(key: String): String? = values[key]
}

/** Tries each source in order; the first non-null value wins. */
class CompositeConfigSource(private val sources: List<ConfigSource>) : ConfigSource {
    override fun get(key: String): String? {
        for (source in sources) {
            source.get(key)?.let { return it }
        }
        return null
    }
}
