package ai.droidcommand.remote

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.TreeMap

/**
 * An [HttpTransport]/[StreamingHttpTransport] built on [java.net.HttpURLConnection], which exists on
 * both a desktop JVM and Android. [JdkHttpTransport] cannot run on Android: it depends on
 * `java.net.http`, which Android's runtime does not ship.
 *
 * Behaviour matches [JdkHttpTransport] where it matters to [RemoteClient]: redirects are NOT followed
 * (so an `Authorization` header can never be replayed to another origin), a non-2xx streaming response
 * is collected whole into [HttpResponseSpec.body], response header names are case-insensitive, and I/O
 * failures — including timeouts, as [SocketTimeoutException] — are thrown as [IOException].
 *
 * Differences, stated rather than hidden: no certificate pinning or mutual TLS (use [JdkHttpTransport]
 * on a JVM for those); [HttpRequestSpec.requestTimeoutMillis] is applied as a per-read timeout, not a
 * whole-request deadline, so a slow trickle can outlast it; only methods [HttpURLConnection] supports
 * (GET/POST/PUT/DELETE/HEAD/OPTIONS/TRACE) work — PATCH does not.
 */
class HttpUrlConnectionTransport : StreamingHttpTransport {
    override fun send(request: HttpRequestSpec): HttpResponseSpec = execute(request) { connection, status ->
        val stream = if (status in 200..399) connection.inputStream else connection.errorStream
        HttpResponseSpec(status, headersOf(connection), stream?.use { readAll(it) }.orEmpty())
    }

    override fun sendStreaming(request: HttpRequestSpec, onLine: (String) -> Unit): HttpResponseSpec =
        execute(request) { connection, status ->
            val headers = headersOf(connection)
            if (status !in 200..299) {
                val body = (connection.errorStream ?: runCatching { connection.inputStream }.getOrNull())?.use { readAll(it) }.orEmpty()
                return@execute HttpResponseSpec(status, headers, body)
            }
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader -> reader.forEachLine(onLine) }
            HttpResponseSpec(status, headers, "")
        }

    private fun <T> execute(request: HttpRequestSpec, handle: (HttpURLConnection, Int) -> T): T {
        val connection = URL(request.url).openConnection() as? HttpURLConnection
            ?: throw IOException("Not an HTTP(S) URL: ${request.url}")
        try {
            connection.requestMethod = request.method
            connection.connectTimeout = request.connectTimeoutMillis.toInt()
            connection.readTimeout = request.requestTimeoutMillis.toInt()
            connection.instanceFollowRedirects = false
            request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            request.body?.let { body ->
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            return handle(connection, connection.responseCode)
        } finally {
            connection.disconnect()
        }
    }

    private fun readAll(stream: InputStream): String = BufferedReader(stream.reader(Charsets.UTF_8)).readText()

    private fun headersOf(connection: HttpURLConnection): Map<String, String> {
        val headers = TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER)
        connection.headerFields.forEach { (name, values) -> if (name != null) headers[name] = values.joinToString(",") }
        return headers
    }
}

/**
 * True for a timeout failure on any platform. Does not reference `java.net.http.HttpTimeoutException`
 * as a class literal or `is` check — that class is absent on Android, where evaluating it would throw
 * `NoClassDefFoundError` exactly when a network failure needs classifying — so it matches by name.
 */
fun Throwable?.isTimeoutFailure(): Boolean =
    this is SocketTimeoutException || this?.javaClass?.name == "java.net.http.HttpTimeoutException"
