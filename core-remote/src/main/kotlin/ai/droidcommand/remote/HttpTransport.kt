package ai.droidcommand.remote

data class HttpRequestSpec(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val connectTimeoutMillis: Long = 10_000,
    val requestTimeoutMillis: Long = 30_000,
)

data class HttpResponseSpec(
    val statusCode: Int,
    val headers: Map<String, String>,
    val body: String,
)

/** The transport boundary [RemoteClient] talks through. [JdkHttpTransport] is the real implementation. */
interface HttpTransport {
    fun send(request: HttpRequestSpec): HttpResponseSpec
}

/**
 * An [HttpTransport] that can also deliver a response body incrementally,
 * one line at a time, as it arrives — what a Server-Sent Events stream
 * (see [ServerSentEventParser]) needs.
 *
 * Contract for [sendStreaming]: for a 2xx response every body line is
 * passed to [onLine] as it arrives, in order, and the returned
 * [HttpResponseSpec.body] is empty. For any other status nothing is
 * passed to [onLine]; the whole body is collected into
 * [HttpResponseSpec.body] instead, so a caller can report the server's
 * error message the same way [send] allows. Like [send], an I/O failure
 * (including a timeout) is thrown as an [java.io.IOException] — possibly
 * after some lines were already delivered.
 */
interface StreamingHttpTransport : HttpTransport {
    fun sendStreaming(request: HttpRequestSpec, onLine: (String) -> Unit): HttpResponseSpec
}
