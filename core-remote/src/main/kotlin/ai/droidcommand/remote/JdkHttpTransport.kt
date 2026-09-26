package ai.droidcommand.remote

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.time.Duration
import javax.net.ssl.SSLContext

/**
 * Real HTTP transport backed by [java.net.http.HttpClient] — no external
 * dependency required. A blocking call: [send] returns once the response
 * arrives or throws [java.io.IOException] (including a timeout, which the
 * JDK surfaces as [java.net.http.HttpTimeoutException], an IOException
 * subtype, and a TLS handshake failure — e.g. a pinning mismatch or an
 * unpresented/untrusted mutual-TLS client certificate — surfaced as
 * [javax.net.ssl.SSLHandshakeException], also an IOException subtype) once
 * [HttpRequestSpec.requestTimeoutMillis] elapses or the handshake fails.
 *
 * [certificatePinner] and [mutualTls] are independent, composable trust/
 * identity mechanisms: [mutualTls] can supply a client certificate to
 * present ([MutualTlsConfig.keyManagers]) regardless of which trust
 * mechanism is in effect. For validating the *server*, [certificatePinner]
 * takes precedence when both are supplied — pinning a specific key is a
 * stricter, narrower check than trusting a private CA — falling back to
 * [MutualTlsConfig.trustManagers] when only mTLS configures one, and to
 * the platform's ordinary default CA trust when neither does. Supplying
 * neither leaves every connection on the platform's ordinary default
 * `SSLContext` with no client certificate, exactly as before either
 * mechanism existed.
 */
class JdkHttpTransport(
    private val certificatePinner: CertificatePinner? = null,
    private val mutualTls: MutualTlsConfig? = null,
) : StreamingHttpTransport {
    override fun send(request: HttpRequestSpec): HttpResponseSpec {
        val response = buildClient(request).send(buildRequest(request), HttpResponse.BodyHandlers.ofString())
        return HttpResponseSpec(response.statusCode(), headersOf(response), response.body())
    }

    /**
     * Streams a 2xx body line by line via [java.net.http.HttpResponse.BodyHandlers.ofLines];
     * see [StreamingHttpTransport] for the contract. [HttpRequestSpec.requestTimeoutMillis]
     * bounds the wait for the response *headers* only — the JDK does not
     * apply it to reading the body — so a server that stops sending
     * mid-stream is only bounded by the connection itself.
     */
    override fun sendStreaming(request: HttpRequestSpec, onLine: (String) -> Unit): HttpResponseSpec {
        val response = buildClient(request).send(buildRequest(request), HttpResponse.BodyHandlers.ofLines())
        val headers = headersOf(response)
        response.body().use { lines ->
            if (response.statusCode() !in 200..299) {
                return HttpResponseSpec(response.statusCode(), headers, lines.toList().joinToString("\n"))
            }
            try {
                lines.forEach(onLine)
            } catch (e: java.io.UncheckedIOException) {
                // Stream<String> can only surface a read failure unchecked; restore the
                // IOException the StreamingHttpTransport contract promises.
                throw e.cause ?: e
            }
        }
        return HttpResponseSpec(response.statusCode(), headers, "")
    }

    private fun buildClient(request: HttpRequestSpec): HttpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(request.connectTimeoutMillis))
            .apply { if (certificatePinner != null || mutualTls != null) sslContext(buildSslContext()) }
            .build()

    private fun buildRequest(request: HttpRequestSpec): HttpRequest {
        val bodyPublisher = if (request.body != null) {
            HttpRequest.BodyPublishers.ofString(request.body)
        } else {
            HttpRequest.BodyPublishers.noBody()
        }

        val requestBuilder = HttpRequest.newBuilder(URI.create(request.url))
            .timeout(Duration.ofMillis(request.requestTimeoutMillis))
            .method(request.method, bodyPublisher)

        request.headers.forEach { (name, value) -> requestBuilder.header(name, value) }
        return requestBuilder.build()
    }

    private fun headersOf(response: HttpResponse<*>): Map<String, String> {
        // HTTP header names are case-insensitive per spec; java.net.http.HttpHeaders
        // does not guarantee the sender's exact casing survives into map(), so the
        // map handed back here must be looked up case-insensitively too.
        val headers = java.util.TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER)
        response.headers().map().forEach { (name, values) -> headers[name] = values.joinToString(",") }
        return headers
    }

    private fun buildSslContext(): SSLContext {
        val keyManagers = mutualTls?.keyManagers()
        val trustManagers = certificatePinner?.let { arrayOf<javax.net.ssl.TrustManager>(it.trustManager()) }
            ?: mutualTls?.trustManagers()
        return SSLContext.getInstance("TLS").apply {
            init(keyManagers, trustManagers, SecureRandom())
        }
    }
}
