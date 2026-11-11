package ai.droidcommand.llm.pchost

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PcHostCopyTest {
    private val local = PcHostModel("gemma4:latest", PcModelLocation.SERVER_LOCAL)
    private val cloud = PcHostModel("gpt-oss:120b-cloud", PcModelLocation.CLOUD_BACKED, "ollama.com")

    @Test
    fun `the test result separates models on the PC from cloud-backed ones`() {
        assertEquals(
            "Connected to Ollama 0.12.6. 2 models. 1 on your PC, 1 cloud-backed (the PC forwards those to the internet).",
            PcHostCopy.reachable(PcHostKind.OLLAMA, "0.12.6", listOf(local, cloud), "gemma4:latest"),
        )
        assertEquals("Connected to Ollama. 1 model. All of them run on your PC.", PcHostCopy.reachable(PcHostKind.OLLAMA, null, listOf(local), null))
        assertTrue(
            PcHostCopy.reachable(PcHostKind.OPENAI_COMPATIBLE, null, listOf(PcHostModel("m", PcModelLocation.UNKNOWN)), null)
                .contains("does not say where each model runs"),
        )
    }

    @Test
    fun `an empty server and a vanished model are called out`() {
        assertTrue(PcHostCopy.reachable(PcHostKind.OLLAMA, "0.12.6", emptyList(), null).contains("ollama pull"))
        assertTrue(PcHostCopy.reachable(PcHostKind.OLLAMA, null, listOf(local), "old:model").contains("\"old:model\", is not on the server any more"))
    }

    @Test
    fun `the explanation says which workflow sends messages to the PC and which copies a file to the phone`() {
        assertTrue(PcHostCopy.TWO_WORKFLOWS.contains("every message is sent to the PC"))
        assertTrue(PcHostCopy.TWO_WORKFLOWS.contains("import a .gguf model file onto this phone"))
    }
}
