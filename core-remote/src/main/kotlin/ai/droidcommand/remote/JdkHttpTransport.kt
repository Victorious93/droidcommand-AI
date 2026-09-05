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
 * subtype, and a TLS handshake failure — e.g. an unpresented or untrusted
 * mutual-TLS client certificate — surfaced as
 * [javax.net.ssl.SSLHandshakeException], also an IOException subtype) once
 * [HttpRequestSpec.requestTimeoutMillis] elapses or the handshake fails.
 *
 * [mutualTls], when supplied, configures this instance's TLS handshake per
 * [MutualTlsConfig] — presenting a client certificate, trusting the server
 * against a private CA, or both. Omitted, every connection uses the
 * platform's ordinary default `SSLContext` with no client certificate.
 */
class JdkHttpTransport(private val mutualTls: MutualTlsConfig? = null) : HttpTransport {
    override fun send(request: HttpRequestSpec): HttpResponseSpec {
        val client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(request.connectTimeoutMillis))
            .apply { mutualTls?.let { sslContext(mutualTlsSslContext(it)) } }
            .build()

        val bodyPublisher = if (request.body != null) {
            HttpRequest.BodyPublishers.ofString(request.body)
        } else {
            HttpRequest.BodyPublishers.noBody()
        }

        val requestBuilder = HttpRequest.newBuilder(URI.create(request.url))
            .timeout(Duration.ofMillis(request.requestTimeoutMillis))
            .method(request.method, bodyPublisher)

        request.headers.forEach { (name, value) -> requestBuilder.header(name, value) }

        val response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())

        // HTTP header names are case-insensitive per spec; java.net.http.HttpHeaders
        // does not guarantee the sender's exact casing survives into map(), so the
        // map handed back here must be looked up case-insensitively too.
        val headers = java.util.TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER)
        response.headers().map().forEach { (name, values) -> headers[name] = values.joinToString(",") }
        return HttpResponseSpec(response.statusCode(), headers, response.body())
    }

    private fun mutualTlsSslContext(config: MutualTlsConfig): SSLContext =
        SSLContext.getInstance("TLS").apply {
            init(config.keyManagers(), config.trustManagers(), SecureRandom())
        }
}
