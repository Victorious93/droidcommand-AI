package ai.droidcommand.remote

import java.security.KeyStore
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory

/**
 * Configures mutual TLS for [JdkHttpTransport]: [keyStore]/[keyPassword]
 * hold the private key and certificate chain this client presents during
 * the handshake, so the server can authenticate the client the same way
 * ordinary HTTPS has the client authenticate the server. [trustStore],
 * when supplied, replaces the platform's default CA trust manager for
 * validating the server's own certificate — the common real-world shape
 * for mTLS, where both sides of a private service (an internal build or
 * LLM server, say) present certificates issued by the same private CA
 * rather than a public one. When [trustStore] is omitted, the server is
 * still validated against the platform's ordinary CA trust store, exactly
 * as it would be for a normal HTTPS connection with client
 * authentication layered on top.
 *
 * At least one of [keyStore] (to present a client identity) or
 * [trustStore] (to validate the server against a private CA) must be
 * supplied — a config with neither would change nothing over the
 * unpinned/no-mTLS default, so constructing one would only be misleading.
 * Neither keystore is read from a file path by this class — the caller
 * loads it, matching core-config's rule that a secret is never captured
 * from a raw path at construction time.
 */
class MutualTlsConfig(
    private val keyStore: KeyStore? = null,
    private val keyPassword: CharArray? = null,
    private val trustStore: KeyStore? = null,
) {
    init {
        require(keyStore != null || trustStore != null) {
            "MutualTlsConfig requires at least a keyStore (to present a client certificate) or a trustStore (to validate the server), or both"
        }
        require((keyStore == null) == (keyPassword == null)) {
            "keyStore and keyPassword must be supplied together"
        }
    }

    fun keyManagers(): Array<KeyManager>? = keyStore?.let { store ->
        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, keyPassword) }.keyManagers
    }

    fun trustManagers(): Array<TrustManager>? = trustStore?.let { store ->
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }.trustManagers
    }
}
