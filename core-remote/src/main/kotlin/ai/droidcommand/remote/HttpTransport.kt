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
