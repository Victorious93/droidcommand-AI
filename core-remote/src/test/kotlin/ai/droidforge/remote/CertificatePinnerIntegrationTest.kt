package ai.droidforge.remote

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Proves [CertificatePinner] against a real TLS handshake, not a scripted
 * fake: a real self-signed certificate (generated with the JDK's own
 * `keytool`, the same "reuse a real JDK tool via a real subprocess"
 * approach `core-build-local` uses for `javac`), a real
 * `com.sun.net.httpserver.HttpsServer` presenting it, and a real
 * [JdkHttpTransport] connecting over loopback — the correct pin lets the
 * handshake complete, and a wrong one makes it fail for real.
 */
class CertificatePinnerIntegrationTest {
    private val workDir = Files.createTempDirectory("certificate-pinner-test")
    private var server: HttpsServer? = null

    @AfterTest
    fun tearDown() {
        server?.stop(0)
        workDir.toFile().deleteRecursively()
    }

    private fun generateSelfSignedKeystore(): java.nio.file.Path {
        val keystorePath = workDir.resolve("test.p12")
        val process = ProcessBuilder(
            "keytool", "-genkeypair",
            "-alias", "test",
            "-keyalg", "RSA", "-keysize", "2048",
            "-validity", "1",
            "-keystore", keystorePath.toString(),
            "-storetype", "PKCS12",
            "-storepass", "changeit", "-keypass", "changeit",
            "-dname", "CN=localhost",
            "-ext", "SAN=dns:localhost,ip:127.0.0.1",
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        check(exitCode == 0) { "keytool failed with exit $exitCode: $output" }
        return keystorePath
    }

    private fun loadKeyStore(path: java.nio.file.Path): KeyStore =
        KeyStore.getInstance("PKCS12").apply { Files.newInputStream(path).use { load(it, "changeit".toCharArray()) } }

    private fun startHttpsServer(keyStore: KeyStore): HttpsServer {
        val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keyManagerFactory.init(keyStore, "changeit".toCharArray())
        val sslContext = SSLContext.getInstance("TLS").apply { init(keyManagerFactory.keyManagers, null, null) }

        val httpsServer = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        httpsServer.httpsConfigurator = HttpsConfigurator(sslContext)
        httpsServer.createContext("/ping") { exchange ->
            val body = "pong".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        httpsServer.start()
        server = httpsServer
        return httpsServer
    }

    @Test
    fun `the correct pin lets a real TLS handshake against a self-signed certificate complete`() {
        val keyStore = loadKeyStore(generateSelfSignedKeystore())
        val certificate = keyStore.getCertificate("test") as X509Certificate
        val correctPin = CertificatePinner.sha256SpkiHex(certificate)
        val httpsServer = startHttpsServer(keyStore)

        val transport = JdkHttpTransport(CertificatePinner(setOf(correctPin)))
        val response = transport.send(HttpRequestSpec(method = "GET", url = "https://127.0.0.1:${httpsServer.address.port}/ping"))

        assertEquals(200, response.statusCode)
        assertEquals("pong", response.body)
    }

    @Test
    fun `a wrong pin makes the real TLS handshake fail rather than silently trusting the server`() {
        val keyStore = loadKeyStore(generateSelfSignedKeystore())
        val httpsServer = startHttpsServer(keyStore)
        val wrongPin = "0".repeat(64)

        val transport = JdkHttpTransport(CertificatePinner(setOf(wrongPin)))
        assertFailsWith<IOException> {
            transport.send(HttpRequestSpec(method = "GET", url = "https://127.0.0.1:${httpsServer.address.port}/ping"))
        }
    }

    @Test
    fun `with no pinner configured, JdkHttpTransport falls back to ordinary CA trust and rejects the same self-signed certificate`() {
        val keyStore = loadKeyStore(generateSelfSignedKeystore())
        val httpsServer = startHttpsServer(keyStore)

        val transport = JdkHttpTransport()
        assertFailsWith<IOException> {
            transport.send(HttpRequestSpec(method = "GET", url = "https://127.0.0.1:${httpsServer.address.port}/ping"))
        }
    }

    @Test
    fun `CertificatePinner rejects construction with no pins at all`() {
        assertFailsWith<IllegalArgumentException> { CertificatePinner(emptySet()) }
    }

    @Test
    fun `sha256SpkiHex is deterministic and produces a 64-character lowercase hex string`() {
        val keyStore = loadKeyStore(generateSelfSignedKeystore())
        val certificate = keyStore.getCertificate("test") as X509Certificate

        val pin = CertificatePinner.sha256SpkiHex(certificate)

        assertEquals(64, pin.length)
        assertEquals(pin, pin.lowercase())
        assertEquals(pin, CertificatePinner.sha256SpkiHex(certificate))
    }
}
