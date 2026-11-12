package ai.droidcommand.llm.pchost

import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PcHostSettingsStoreTest {
    @Test
    fun `both endpoints survive a restart and are independent`() {
        val file = Files.createTempDirectory("pchost").resolve("pc-hosts.properties")
        val ollama = PcHostEndpoint("http://192.168.1.20:11434", "gemma4:latest", PcModelLocation.SERVER_LOCAL)
        val other = PcHostEndpoint("http://100.64.0.7:8080", "qwen: odd = name", PcModelLocation.CLOUD_BACKED, "ollama.com")
        FilePcHostSettingsStore(file).apply {
            save(PcHostKind.OLLAMA, ollama)
            save(PcHostKind.OPENAI_COMPATIBLE, other)
        }
        val reopened = FilePcHostSettingsStore(file)
        assertEquals(ollama, reopened.load(PcHostKind.OLLAMA))
        assertEquals(other, reopened.load(PcHostKind.OPENAI_COMPATIBLE))
        assertEquals("gemma4:latest", reopened.load(PcHostKind.OLLAMA)!!.target(PcHostKind.OLLAMA)!!.model)

        reopened.save(PcHostKind.OLLAMA, null)
        assertNull(FilePcHostSettingsStore(file).load(PcHostKind.OLLAMA))
        assertEquals(other, FilePcHostSettingsStore(file).load(PcHostKind.OPENAI_COMPATIBLE))
    }

    @Test
    fun `nothing saved, no model, or an address that no longer parses gives no chat target`() {
        val file = Files.createTempDirectory("pchost").resolve("pc-hosts.properties")
        assertNull(FilePcHostSettingsStore(file).load(PcHostKind.OLLAMA))
        assertNull(PcHostEndpoint("http://192.168.1.20:11434").target(PcHostKind.OLLAMA))
        assertNull(PcHostEndpoint("http://8.8.8.8:11434", "m").target(PcHostKind.OLLAMA))
        file.writeText("ollama.address=\n")
        assertNull(FilePcHostSettingsStore(file).load(PcHostKind.OLLAMA))
    }
}
