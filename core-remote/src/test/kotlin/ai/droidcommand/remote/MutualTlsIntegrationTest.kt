package ai.droidcommand.remote

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsParameters
import com.sun.net.httpserver.HttpsServer
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import javax.net.ssl.SSLContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Proves [MutualTlsConfig]/[JdkHttpTransport]'s mutual-TLS support against
 * a real TLS handshake, not a scripted fake — the same "reuse a real JDK
 * tool via a real subprocess" standard `core-build-local` applies to
 * `javac`: a real private CA and a real server/client certificate chain,
 * all generated with the JDK's own `keytool`, a real
 * `com.sun.net.httpserver.HttpsServer` requiring client authentication,
 * and a real [JdkHttpTransport] connecting over loopback.
 *
 * The CA/server/client relationship modeled here — both sides presenting
 * certificates issued by the same private CA, rather than a public one —
 * is the common real-world shape for mTLS to an internal service (a build
 * or LLM server), not an artificial test-only setup.
 */
class MutualTlsIntegrationTest {
    private val workDir = Files.createTempDirectory("mutual-tls-test")
    private var server: HttpsServer? = null

    @AfterTest
    fun tearDown() {
        server?.stop(0)
        workDir.toFile().deleteRecursively()
    }

    private fun keytool(vararg args: String) {
        val process = ProcessBuilder("keytool", *args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        check(exitCode == 0) { "keytool ${args.joinToString(" ")} failed with exit $exitCode: $output" }
    }

    private fun loadKeyStore(path: Path, password: String = "changeit"): KeyStore =
        KeyStore.getInstance("PKCS12").apply { Files.newInputStream(path).use { load(it, password.toCharArray()) } }

    /** A real CA + server + client certificate chain, all signed by the same private CA. */
    private class Pki(val caCertPath: Path, val serverKeyStorePath: Path, val clientKeyStorePath: Path, val trustStorePath: Path)

    private fun generatePki(): Pki {
        val caKeyStore = workDir.resolve("ca.p12")
        val caCert = workDir.resolve("ca.crt")
        val trustStore = workDir.resolve("trust.p12")
        val serverKeyStore = workDir.resolve("server.p12")
        val clientKeyStore = workDir.resolve("client.p12")

        keytool(
            "-genkeypair", "-alias", "ca", "-keyalg", "RSA", "-keysize", "2048", "-validity", "3",
            "-keystore", caKeyStore.toString(), "-storetype", "PKCS12", "-storepass", "changeit", "-keypass", "changeit",
            "-dname", "CN=Test Private CA", "-ext", "bc:c",
        )
        keytool("-exportcert", "-alias", "ca", "-keystore", caKeyStore.toString(), "-storepass", "changeit", "-file", caCert.toString(), "-rfc")
        keytool("-importcert", "-alias", "ca", "-keystore", trustStore.toString(), "-storetype", "PKCS12", "-storepass", "changeit", "-file", caCert.toString(), "-noprompt")

        issueCertificate(caKeyStore, caCert, serverKeyStore, alias = "server", dname = "CN=localhost", san = "SAN=dns:localhost,ip:127.0.0.1")
        issueCertificate(caKeyStore, caCert, clientKeyStore, alias = "client", dname = "CN=test-client", san = null)

        return Pki(caCert, serverKeyStore, clientKeyStore, trustStore)
    }

    private fun issueCertificate(caKeyStore: Path, caCert: Path, keyStorePath: Path, alias: String, dname: String, san: String?) {
        val csr = workDir.resolve("$alias.csr")
        val signed = workDir.resolve("$alias.crt")
        val genArgs = mutableListOf(
            "-genkeypair", "-alias", alias, "-keyalg", "RSA", "-keysize", "2048", "-validity", "3",
            "-keystore", keyStorePath.toString(), "-storetype", "PKCS12", "-storepass", "changeit", "-keypass", "changeit",
            "-dname", dname,
        )
        keytool(*genArgs.toTypedArray())

        val csrArgs = mutableListOf("-certreq", "-alias", alias, "-keystore", keyStorePath.toString(), "-storepass", "changeit", "-file", csr.toString())
        san?.let { csrArgs += listOf("-ext", it) }
        keytool(*csrArgs.toTypedArray())

        val gencertArgs = mutableListOf(
            "-gencert", "-alias", "ca", "-keystore", caKeyStore.toString(), "-storepass", "changeit",
            "-infile", csr.toString(), "-outfile", signed.toString(), "-rfc", "-validity", "3",
        )
        san?.let { gencertArgs += listOf("-ext", it) }
        keytool(*gencertArgs.toTypedArray())

        keytool("-importcert", "-alias", "ca", "-keystore", keyStorePath.toString(), "-storepass", "changeit", "-file", caCert.toString(), "-noprompt")
        keytool("-importcert", "-alias", alias, "-keystore", keyStorePath.toString(), "-storepass", "changeit", "-file", signed.toString())
    }

    private fun startServerRequiringClientAuth(serverKeyStore: KeyStore, trustStore: KeyStore): HttpsServer {
        val keyManagerFactory = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm())
        keyManagerFactory.init(serverKeyStore, "changeit".toCharArray())
        val trustManagerFactory = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm())
        trustManagerFactory.init(trustStore)
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(keyManagerFactory.keyManagers, trustManagerFactory.trustManagers, null)
        }

        val httpsServer = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        httpsServer.httpsConfigurator = object : HttpsConfigurator(sslContext) {
            override fun configure(params: HttpsParameters) {
                params.setSSLParameters(sslContext.defaultSSLParameters.apply { needClientAuth = true })
            }
        }
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
    fun `a client certificate signed by the CA the server trusts completes a real mutually-authenticated handshake`() {
        val pki = generatePki()
        val httpsServer = startServerRequiringClientAuth(loadKeyStore(pki.serverKeyStorePath), loadKeyStore(pki.trustStorePath))

        val transport = JdkHttpTransport(
            MutualTlsConfig(
                keyStore = loadKeyStore(pki.clientKeyStorePath),
                keyPassword = "changeit".toCharArray(),
                trustStore = loadKeyStore(pki.trustStorePath),
            ),
        )

        val response = transport.send(HttpRequestSpec(method = "GET", url = "https://127.0.0.1:${httpsServer.address.port}/ping"))

        assertEquals(200, response.statusCode)
        assertEquals("pong", response.body)
    }

    @Test
    fun `no client certificate presented makes the handshake fail against a server requiring client auth`() {
        val pki = generatePki()
        val httpsServer = startServerRequiringClientAuth(loadKeyStore(pki.serverKeyStorePath), loadKeyStore(pki.trustStorePath))

        // Trusts the server (via the CA) but presents no client identity at all.
        val transport = JdkHttpTransport(MutualTlsConfig(trustStore = loadKeyStore(pki.trustStorePath)))

        assertFailsWith<IOException> {
            transport.send(HttpRequestSpec(method = "GET", url = "https://127.0.0.1:${httpsServer.address.port}/ping"))
        }
    }

    @Test
    fun `with no trust store configured, a private-CA-signed server certificate is rejected by ordinary platform CA trust`() {
        val pki = generatePki()
        val httpsServer = startServerRequiringClientAuth(loadKeyStore(pki.serverKeyStorePath), loadKeyStore(pki.trustStorePath))

        // Presents a valid client certificate but never told to trust the private CA that signed the server's certificate.
        val transport = JdkHttpTransport(
            MutualTlsConfig(keyStore = loadKeyStore(pki.clientKeyStorePath), keyPassword = "changeit".toCharArray()),
        )

        assertFailsWith<IOException> {
            transport.send(HttpRequestSpec(method = "GET", url = "https://127.0.0.1:${httpsServer.address.port}/ping"))
        }
    }

    @Test
    fun `MutualTlsConfig rejects construction with neither a keyStore nor a trustStore`() {
        assertFailsWith<IllegalArgumentException> { MutualTlsConfig() }
    }

    @Test
    fun `MutualTlsConfig rejects a keyStore supplied without its password`() {
        val pki = generatePki()
        assertFailsWith<IllegalArgumentException> { MutualTlsConfig(keyStore = loadKeyStore(pki.clientKeyStorePath)) }
    }
}
