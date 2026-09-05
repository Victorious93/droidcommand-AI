package ai.droidforge.remote

import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * Trusts a TLS peer solely by whether its certificate's public key
 * (SubjectPublicKeyInfo, DER-encoded) hashes to one of [pinnedSha256Spki] —
 * the same "pin the key, not the CA chain" approach as OkHttp's
 * `CertificatePinner`. This *replaces* ordinary CA chain validation rather
 * than adding to it: it exists for a caller who already knows exactly which
 * key a specific endpoint (a configured build/LLM server) must present, not
 * as a general-purpose trust store. [pinnedSha256Spki] must be non-empty —
 * a pinner with no pins would trust nothing, which is never useful to
 * construct, so that case is rejected at construction rather than silently
 * denying every connection later.
 */
class CertificatePinner(private val pinnedSha256Spki: Set<String>) {
    init {
        require(pinnedSha256Spki.isNotEmpty()) { "CertificatePinner requires at least one pin" }
    }

    fun trustManager(): X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
            throw CertificateException("CertificatePinner is a client-side trust manager; it does not trust client certificates")
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
            if (chain.isEmpty()) {
                throw CertificateException("Server presented an empty certificate chain")
            }
            val presentedPins = chain.map { sha256SpkiHex(it) }
            if (presentedPins.none { it in pinnedSha256Spki }) {
                throw CertificateException(
                    "None of the server's certificate(s) matched a pinned public key. " +
                        "Presented: $presentedPins; pinned: $pinnedSha256Spki",
                )
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    companion object {
        /** The lowercase-hex SHA-256 of [certificate]'s DER-encoded SubjectPublicKeyInfo — the value a pin is compared against. */
        fun sha256SpkiHex(certificate: X509Certificate): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded)
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
