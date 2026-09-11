package ai.droidcommand.config

/**
 * Composes any existing, **unmodified** [ConfigSource] (`EnvConfigSource`/
 * `MapConfigSource`/`CompositeConfigSource`) with a [SecretsVault] into a
 * real [EnhancedConfigSource]. `get(key)` is delegated via Kotlin
 * interface delegation rather than re-implemented — no existing
 * [ConfigSource] implementation is touched by this class.
 */
class DefaultEnhancedConfigSource(
    delegate: ConfigSource,
    private val vault: SecretsVault,
) : EnhancedConfigSource, ConfigSource by delegate {
    override fun getSecretsVault(): SecretsVault = vault
}
