package ai.droidcommand.llm.pchost

import ai.droidcommand.remote.HttpRequestSpec
import ai.droidcommand.remote.HttpResponseSpec
import ai.droidcommand.remote.HttpTransport
import ai.droidcommand.remote.HttpUrlConnectionTransport
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "Test connection" and model discovery against a controlled server, over the transport the phone uses. */
class PcHostClientTest {
    private val client = PcHostClient(HttpUrlConnectionTransport(), connectTimeoutMillis = 2_000, requestTimeoutMillis = 1_000)

    private fun address(kind: PcHostKind, url: String) = (PcHostAddress.parse(kind, url) as PcHostAddressParse.Ok).address

    // The field names are the ones in Ollama's published API description (ModelSummary: name, remote_model, remote_host, size, details).
    private val tags = """{"models":[
        {"name":"gemma4:latest","model":"gemma4:latest","size":9608350245,"digest":"c6eb","details":{"format":"gguf","parameter_size":"8.0B","quantization_level":"Q4_K_M"}},
        {"name":"gpt-oss:120b-cloud","model":"gpt-oss:120b-cloud","remote_model":"gpt-oss:120b","remote_host":"https://ollama.com:443","size":384},
        {"name":"qwen:cloud","model":"qwen:cloud","size":300}
    ]}"""

    @Test
    fun `Ollama lists its models and says which ones the PC only forwards`() {
        FakePcServer { request, conn ->
            when (request.path) {
                "/api/tags" -> conn.respond(200, tags)
                "/api/version" -> conn.respond(200, """{"version":"0.12.6"}""")
                else -> conn.respond(404, "{}")
            }
        }.use { server ->
            val probe = assertIs<PcHostProbe.Reachable>(client.probe(PcHostKind.OLLAMA, address(PcHostKind.OLLAMA, server.address), key = null))
            assertEquals("0.12.6", probe.serverVersion)
            assertEquals(listOf("gemma4:latest", "gpt-oss:120b-cloud", "qwen:cloud"), probe.models.map { it.id })
            val (local, cloud, cloudByName) = probe.models
            assertEquals(PcModelLocation.SERVER_LOCAL, local.location)
            assertEquals("8.0B · Q4_K_M · 9.6 GB", local.detail)
            assertEquals("On your PC", local.locationLabel())
            assertEquals(PcModelLocation.CLOUD_BACKED, cloud.location)
            assertEquals("ollama.com", cloud.upstream)
            assertTrue(cloud.locationLabel().startsWith("Cloud-backed"))
            assertEquals(PcModelLocation.CLOUD_BACKED, cloudByName.location)
            // No key saved: no Authorization header is sent to a server that needs none.
            assertTrue(server.requests.none { it.headers.containsKey("authorization") })
        }
    }

    @Test
    fun `an OpenAI-compatible server lists ids and the location is reported as unknown`() {
        FakePcServer { request, conn ->
            if (request.path == "/v1/models" && request.headers["authorization"] == "Bearer sk-home-server-key") {
                conn.respond(200, """{"object":"list","data":[{"id":"qwen2.5-7b-instruct","object":"model","owned_by":"llamacpp"}]}""")
            } else {
                conn.respond(401, """{"error":{"message":"Invalid API Key","type":"authentication_error"}}""")
            }
        }.use { server ->
            val addr = address(PcHostKind.OPENAI_COMPATIBLE, server.address + "/v1")
            val probe = assertIs<PcHostProbe.Reachable>(client.probe(PcHostKind.OPENAI_COMPATIBLE, addr, "sk-home-server-key"))
            assertEquals(PcHostModel("qwen2.5-7b-instruct", PcModelLocation.UNKNOWN, detail = "owner: llamacpp"), probe.models.single())
            assertNull(probe.serverVersion)

            val noKey = assertIs<PcHostProbe.Failed>(client.probe(PcHostKind.OPENAI_COMPATIBLE, addr, null))
            assertEquals(PcHostProblem.AUTHENTICATION, noKey.problem)
            assertTrue(noKey.message.contains("expects an API key"))
            val wrongKey = assertIs<PcHostProbe.Failed>(client.probe(PcHostKind.OPENAI_COMPATIBLE, addr, "sk-wrong-key-000000"))
            assertTrue(wrongKey.message.contains("saved key was not accepted"))
            assertFalse(wrongKey.message.contains("sk-wrong-key-000000"))
        }
    }

    @Test
    fun `a server of another kind or another program is reported as such`() {
        FakePcServer { request, conn ->
            if (request.path == "/v1/models") conn.respond(200, "<html>router login</html>", "text/html") else conn.respond(404, "404 page not found")
        }.use { server ->
            val wrongKind = assertIs<PcHostProbe.Failed>(client.probe(PcHostKind.OLLAMA, address(PcHostKind.OLLAMA, server.address), null))
            assertEquals(PcHostProblem.NOT_COMPATIBLE, wrongKind.problem)
            val notJson = assertIs<PcHostProbe.Failed>(client.probe(PcHostKind.OPENAI_COMPATIBLE, address(PcHostKind.OPENAI_COMPATIBLE, server.address), null))
            assertEquals(PcHostProblem.NOT_COMPATIBLE, notJson.problem)
        }
    }

    @Test
    fun `nothing listening is unreachable and the message is about the PC not the internet`() {
        val addr = address(PcHostKind.OLLAMA, "127.0.0.1:${FakePcServer.closedPort()}")
        val failed = assertIs<PcHostProbe.Failed>(client.probe(PcHostKind.OLLAMA, addr, null))
        assertEquals(PcHostProblem.UNREACHABLE, failed.problem)
        assertTrue(failed.message.startsWith(PcHostErrors.PREFIX))
        assertTrue(failed.message.contains("not about internet access"))
        assertTrue(failed.message.contains("on and awake"))
    }

    @Test
    fun `an asleep PC (connect times out) is unreachable and a silent one is a timeout`() {
        val asleep = PcHostClient(Throwing(SocketTimeoutException("Connect timed out")))
        val addr = address(PcHostKind.OLLAMA, "192.168.1.20")
        assertEquals(PcHostProblem.UNREACHABLE, assertIs<PcHostProbe.Failed>(asleep.probe(PcHostKind.OLLAMA, addr, null)).problem)
        val noRoute = PcHostClient(Throwing(java.net.NoRouteToHostException("No route to host")))
        assertEquals(PcHostProblem.UNREACHABLE, assertIs<PcHostProbe.Failed>(noRoute.probe(PcHostKind.OLLAMA, addr, null)).problem)

        FakePcServer { _, conn -> conn.awaitClientGone(5_000) }.use { server ->
            val silent = assertIs<PcHostProbe.Failed>(client.probe(PcHostKind.OLLAMA, address(PcHostKind.OLLAMA, server.address), null))
            assertEquals(PcHostProblem.TIMEOUT, silent.problem)
        }
    }

    @Test
    fun `plain http to a name on the public internet is refused before any request`() {
        var sent = false
        val transport = object : HttpTransport {
            override fun send(request: HttpRequestSpec): HttpResponseSpec {
                sent = true
                return HttpResponseSpec(200, emptyMap(), "{}")
            }
        }
        val guarded = PcHostClient(transport, resolve = { listOf(java.net.InetAddress.getByName("93.184.216.34")) })
        val failed = assertIs<PcHostProbe.Failed>(guarded.probe(PcHostKind.OLLAMA, address(PcHostKind.OLLAMA, "models.example.com"), "sk-some-key-123456"))
        assertEquals(PcHostProblem.CLEARTEXT_REFUSED, failed.problem)
        assertFalse(sent)
    }

    private class Throwing(private val failure: java.io.IOException) : HttpTransport {
        override fun send(request: HttpRequestSpec): HttpResponseSpec = throw failure
    }
}
