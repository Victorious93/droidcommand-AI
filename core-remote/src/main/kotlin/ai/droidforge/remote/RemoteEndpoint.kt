package ai.droidforge.remote

class InsecureEndpointRejected(url: String) :
    IllegalArgumentException("Endpoint '$url' is not HTTPS and requireHttps is true")

/**
 * Where a [RemoteClient] is allowed to talk. [resolve] is the only way a
 * request URL is built — a caller supplies a relative path, never an
 * absolute URL, so a request can never be redirected to an unintended host
 * by construction, not merely by a runtime check. [requireHttps] defaults
 * to true; set it false only for a deliberately local/loopback endpoint
 * (e.g. a dev build server on localhost).
 */
class RemoteEndpoint(val baseUrl: String, requireHttps: Boolean = true) {
    init {
        require(baseUrl.isNotBlank()) { "baseUrl must not be blank" }
        if (requireHttps && !baseUrl.startsWith("https://")) {
            throw InsecureEndpointRejected(baseUrl)
        }
    }

    fun resolve(path: String): String {
        val trimmedBase = baseUrl.trimEnd('/')
        val trimmedPath = path.trimStart('/')
        return "$trimmedBase/$trimmedPath"
    }
}
