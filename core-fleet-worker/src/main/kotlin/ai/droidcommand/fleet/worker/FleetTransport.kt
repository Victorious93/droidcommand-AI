package ai.droidcommand.fleet.worker

import ai.droidcommand.remote.CertificatePinner
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext

/** A transport failure with the coordinator's structured error when one was returned. */
class FleetTransportException(val httpStatus: Int, val code: String?, message: String, cause: Throwable? = null) : IOException(message, cause) {
    val isRevoked get() = code == "revoked"
    val isUnauthorized get() = httpStatus == 401
}

interface FleetTransport {
    fun enroll(envelope: JsonObject): JsonObject
    fun exchange(credential: String, body: JsonObject): JsonObject
    fun upload(credential: String, jobId: String, attempt: Int, name: String, sha256: String, bytes: ByteArray)
    fun download(credential: String, artifactId: String): ByteArray
    fun rotate(credential: String): String
}

/**
 * HTTPS to the coordinator with the coordinator's public key PINNED (SPKI SHA-256 from the owner-verified pairing
 * payload), reusing core-remote's [CertificatePinner]. Pinning replaces CA-chain trust for this one endpoint: a different
 * certificate is refused. Hostname verification is not performed because identity is the pinned key, not a DNS name
 * (the coordinator is usually a LAN/VPN IP with a self-signed certificate). TLS verification is NOT disabled.
 */
class HttpsFleetTransport(
    private val baseUrl: String,
    spkiSha256: String,
    private val connectTimeoutMs: Int = 8_000,
    private val readTimeoutMs: Int = 60_000,
) : FleetTransport {
    private val ssl: SSLContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(CertificatePinner(setOf(spkiSha256.lowercase())).trustManager()), SecureRandom())
    }
    private val pinnedHost = HostnameVerifier { _, _ -> true }

    init {
        require(baseUrl.startsWith("https://")) { "fleet coordinator URL must be https" }
    }

    private fun open(path: String, method: String, credential: String?, contentType: String?): HttpsURLConnection =
        (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpsURLConnection).apply {
            sslSocketFactory = ssl.socketFactory
            hostnameVerifier = pinnedHost
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            requestMethod = method
            if (credential != null) setRequestProperty("Authorization", "Bearer $credential")
            if (contentType != null) setRequestProperty("Content-Type", contentType)
            instanceFollowRedirects = false
        }

    private fun fail(c: HttpURLConnection, status: Int): Nothing {
        val text = (c.errorStream?.readBytes() ?: ByteArray(0)).toString(Charsets.UTF_8)
        val err = runCatching { (FleetProtocol.json.parseToJsonElement(text) as JsonObject)["error"] as JsonObject }.getOrNull()
        val code = (err?.get("code") as? kotlinx.serialization.json.JsonPrimitive)?.content
        val msg = (err?.get("message") as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "HTTP $status"
        throw FleetTransportException(status, code, msg)
    }

    private fun json(path: String, credential: String?, body: JsonObject): JsonObject {
        val c = open(path, "POST", credential, "application/json")
        try {
            c.doOutput = true
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val st = c.responseCode
            if (st != 200) fail(c, st)
            return FleetProtocol.json.parseToJsonElement(c.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)) as JsonObject
        } catch (e: FleetTransportException) {
            throw e
        } catch (e: IOException) {
            throw FleetTransportException(0, null, "network: ${e.message}", e)
        } finally {
            c.disconnect()
        }
    }

    override fun enroll(envelope: JsonObject) = json("/v1/enroll", null, envelope)
    override fun exchange(credential: String, body: JsonObject) = json("/v1/exchange", credential, body)
    override fun rotate(credential: String): String =
        (json("/v1/rotate", credential, JsonObject(emptyMap()))["credential"] as kotlinx.serialization.json.JsonPrimitive).content

    override fun upload(credential: String, jobId: String, attempt: Int, name: String, sha256: String, bytes: ByteArray) {
        val c = open("/v1/artifacts/${enc(jobId)}/$attempt/${enc(name)}?sha256=$sha256", "PUT", credential, "application/octet-stream")
        try {
            c.doOutput = true
            c.setFixedLengthStreamingMode(bytes.size.toLong())
            c.outputStream.use { it.write(bytes) }
            val st = c.responseCode
            if (st != 200) fail(c, st)
        } catch (e: FleetTransportException) {
            throw e
        } catch (e: IOException) {
            throw FleetTransportException(0, null, "network: ${e.message}", e)
        } finally {
            c.disconnect()
        }
    }

    override fun download(credential: String, artifactId: String): ByteArray {
        val c = open("/v1/artifacts/${enc(artifactId)}", "GET", credential, null)
        try {
            val st = c.responseCode
            if (st != 200) fail(c, st)
            val out = ByteArrayOutputStream()
            c.inputStream.use { it.copyTo(out) }
            return out.toByteArray()
        } catch (e: FleetTransportException) {
            throw e
        } catch (e: IOException) {
            throw FleetTransportException(0, null, "network: ${e.message}", e)
        } finally {
            c.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
