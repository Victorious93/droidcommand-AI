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
 * subtype, and a pinning failure, which it surfaces as
 * [javax.net.ssl.SSLHandshakeException], also an IOException subtype) once
 * [HttpRequestSpec.requestTimeoutMillis] elapses or the handshake fails.
 *
 * [certificatePinner], when supplied, replaces the platform's default CA
 * trust manager with [CertificatePinner.trustManager] for every connection
 * this instance makes — see that class for why pinning here means "trust
 * this exact key," not "trust anything a CA vouches for."
 */
class JdkHttpTransport(private val certificatePinner: CertificatePinner? = null) : HttpTransport {
    override fun send(request: HttpRequestSpec): HttpResponseSpec {
        val client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(request.connectTimeoutMillis))
            .apply { certificatePinner?.let { sslContext(pinnedSslContext(it)) } }
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

    private fun pinnedSslContext(pinner: CertificatePinner): SSLContext =
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(pinner.trustManager()), SecureRandom())
        }
}
