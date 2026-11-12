package ai.droidcommand.llm.pchost

import ai.droidcommand.llm.openai.testing.StrictChatServer
import ai.droidcommand.llm.openai.testing.StrictReply
import ai.droidcommand.remote.HttpUrlConnectionTransport
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [PcHostClient.toolSupport] against a controlled server answering Ollama's `/api/show` and llama.cpp's `/props`
 * shapes (Ollama `docs/openapi.yaml` `ShowResponse.capabilities`; llama.cpp `tools/server/README.md`
 * `chat_template_caps`). Not a real Ollama or llama.cpp.
 */
class PcHostToolSupportTest {
    private val client = PcHostClient(HttpUrlConnectionTransport(), connectTimeoutMillis = 2_000, requestTimeoutMillis = 3_000)

    private fun check(kind: PcHostKind, path: String?, status: Int = 200, body: String = "{}"): PcHostToolCheck =
        StrictChatServer { StrictReply.Text("unused") }.use { server ->
            path?.let { server.route(it, status) { body } }
            val result = client.toolSupport(kind, (PcHostAddress.parse(kind, server.baseUrl) as PcHostAddressParse.Ok).address, "qwen2.5:7b", key = null)
            assertTrue(server.requests.isEmpty(), "the check must not send a chat request")
            result
        }

    @Test
    fun `Ollama's capability list decides, and its absence is unknown rather than no`() {
        val yes = check(PcHostKind.OLLAMA, "/api/show", body = """{"capabilities":["completion","tools","thinking"]}""")
        assertEquals(PcToolSupport.SUPPORTED, yes.support)
        assertTrue(yes.detail.contains("qwen2.5:7b"), yes.detail)

        val no = check(PcHostKind.OLLAMA, "/api/show", body = """{"capabilities":["completion","vision"]}""")
        assertEquals(PcToolSupport.UNSUPPORTED, no.support)
        assertTrue(no.detail.contains("completion, vision"), no.detail)

        assertEquals(PcToolSupport.UNKNOWN, check(PcHostKind.OLLAMA, "/api/show", body = """{"template":"x"}""").support, "an older Ollama without the field")
        assertEquals(PcToolSupport.UNKNOWN, check(PcHostKind.OLLAMA, "/api/show", status = 404, body = """{"error":"model not found"}""").support)
        assertEquals(PcToolSupport.UNKNOWN, check(PcHostKind.OLLAMA, path = null).support)
    }

    @Test
    fun `llama-server's template capabilities decide, and any other OpenAI-compatible server is unknown`() {
        fun props(tools: Boolean, calls: Boolean) = """{"chat_template_caps":{"supports_tools":$tools,"supports_tool_calls":$calls,"supports_system_role":true}}"""
        assertEquals(PcToolSupport.SUPPORTED, check(PcHostKind.OPENAI_COMPATIBLE, "/props", body = props(tools = true, calls = true)).support)
        assertEquals(PcToolSupport.UNSUPPORTED, check(PcHostKind.OPENAI_COMPATIBLE, "/props", body = props(tools = true, calls = false)).support)
        assertEquals(PcToolSupport.UNSUPPORTED, check(PcHostKind.OPENAI_COMPATIBLE, "/props", body = props(tools = false, calls = false)).support)

        val none = check(PcHostKind.OPENAI_COMPATIBLE, path = null)
        assertEquals(PcToolSupport.UNKNOWN, none.support)
        assertTrue(none.detail.contains("does not report"), none.detail)
        assertEquals(PcToolSupport.UNKNOWN, check(PcHostKind.OPENAI_COMPATIBLE, "/props", body = """{"chat_template":"x"}""").support)
        assertEquals(PcToolSupport.UNKNOWN, check(PcHostKind.OPENAI_COMPATIBLE, "/props", body = """{"chat_template_caps":{}}""").support)
    }

    @Test
    fun `an unreachable server is unknown, never unsupported`() {
        val address = (PcHostAddress.parse(PcHostKind.OLLAMA, "127.0.0.1:${FakePcServer.closedPort()}") as PcHostAddressParse.Ok).address
        assertEquals(PcToolSupport.UNKNOWN, client.toolSupport(PcHostKind.OLLAMA, address, "m", null).support)
    }

    @Test
    fun `what was learned and the owner's switch survive a restart and decide whether tools are offered`() {
        val file = Files.createTempDirectory("pchost-tools").resolve("pc-hosts.properties")
        val saved = PcHostEndpoint("http://192.168.1.20:11434", "qwen2.5:7b", PcModelLocation.SERVER_LOCAL, toolSupport = PcToolSupport.SUPPORTED)
        val forced = PcHostEndpoint("http://192.168.1.20:8080", "local", toolSupport = PcToolSupport.UNKNOWN, toolsForced = true)
        FilePcHostSettingsStore(file).apply {
            save(PcHostKind.OLLAMA, saved)
            save(PcHostKind.OPENAI_COMPATIBLE, forced)
        }
        val reopened = FilePcHostSettingsStore(file)
        assertEquals(saved, reopened.load(PcHostKind.OLLAMA))
        assertEquals(forced, reopened.load(PcHostKind.OPENAI_COMPATIBLE))
        assertTrue(reopened.load(PcHostKind.OLLAMA)!!.target(PcHostKind.OLLAMA)!!.offersTools)
        assertTrue(reopened.load(PcHostKind.OPENAI_COMPATIBLE)!!.target(PcHostKind.OPENAI_COMPATIBLE)!!.offersTools)

        // A file written before these fields existed: unknown, not forced, so tools are not offered.
        Files.writeString(file, "ollama.address=http://192.168.1.20:11434\nollama.model=gemma4\n")
        val old = FilePcHostSettingsStore(file).load(PcHostKind.OLLAMA)!!
        assertEquals(PcToolSupport.UNKNOWN, old.toolSupport)
        assertFalse(old.target(PcHostKind.OLLAMA)!!.offersTools)
    }

    @Test
    fun `the status line never claims support the server did not report`() {
        assertEquals("Tools: supported (reported by the server)", PcHostToolNotices.status(PcToolSupport.SUPPORTED, forced = false))
        assertTrue(PcHostToolNotices.status(PcToolSupport.UNKNOWN, forced = false).contains("not confirmed"))
        assertTrue(PcHostToolNotices.status(PcToolSupport.UNKNOWN, forced = true).contains("you switched them on"))
        assertTrue(PcHostToolNotices.status(PcToolSupport.UNSUPPORTED, forced = true).contains("although the server reports no tool support"))
        assertTrue(PcHostToolNotices.status(PcToolSupport.UNSUPPORTED, forced = false).contains("not supported"))
    }
}
