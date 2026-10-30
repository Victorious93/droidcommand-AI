package ai.droidcommand.llm.local

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GgufQuantTest {
    @Test
    fun `reads common llama cpp quant tags from file names`() {
        assertEquals("Q4_K_M", GgufQuant.fromFileName("tinyllama-1.1b-chat-v1.0.Q4_K_M.gguf"))
        assertEquals("Q8_0", GgufQuant.fromFileName("smollm2-360m-instruct-q8_0.gguf"))
        assertEquals("Q5_K_S", GgufQuant.fromFileName("model-Q5_K_S.gguf"))
        assertEquals("IQ2_XXS", GgufQuant.fromFileName("phi-3-mini-IQ2_XXS.gguf"))
        assertEquals("FP16", GgufQuant.fromFileName("qwen2.5-0.5b-instruct-fp16.gguf"))
    }

    @Test
    fun `unknown names are not guessed`() {
        assertNull(GgufQuant.fromFileName("model.gguf"))
        assertNull(GgufQuant.fromFileName("readme.md"))
        assertNull(GgufQuant.fromFileName(""))
    }
}
