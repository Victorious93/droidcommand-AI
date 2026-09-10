package ai.droidcommand.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PersonaContextTest {
    private fun profile(
        humor: HumorProfile = HumorProfile(present = false),
        commonExpressions: List<String> = emptyList(),
    ) = StyleProfile(
        tone = "warm and direct",
        vocabulary = VocabProfile("moderate", notableVocabulary = listOf("honestly", "basically")),
        sentenceStructure = StructureProfile("short", "punchy, staccato"),
        formality = Formality.CASUAL,
        verbosity = Verbosity.CONCISE,
        humor = humor,
        responseStructure = "answer first, then explain",
        commonExpressions = commonExpressions,
    )

    @Test
    fun `formats every core field`() {
        val text = formatStyleProfile(profile())

        assertTrue(text.contains("Tone: warm and direct"))
        assertTrue(text.contains("Formality: CASUAL"))
        assertTrue(text.contains("Verbosity: CONCISE"))
        assertTrue(text.contains("Vocabulary: moderate"))
        assertTrue(text.contains("Sentence style: short, punchy, staccato"))
        assertTrue(text.contains("Response structure: answer first, then explain"))
    }

    @Test
    fun `humor absent is reported as none, not omitted`() {
        val text = formatStyleProfile(profile(humor = HumorProfile(present = false)))
        assertTrue(text.contains("Humor: none"))
    }

    @Test
    fun `humor present with a style and examples is reported`() {
        val text = formatStyleProfile(profile(humor = HumorProfile(present = true, style = "dry", examples = listOf("deadpan asides"))))

        assertTrue(text.contains("Humor: dry"))
        assertTrue(text.contains("deadpan asides"))
    }

    @Test
    fun `empty commonExpressions is omitted, not printed as an empty list`() {
        val text = formatStyleProfile(profile(commonExpressions = emptyList()))
        assertFalse(text.contains("Common expressions"))
    }

    @Test
    fun `non-empty commonExpressions is included`() {
        val text = formatStyleProfile(profile(commonExpressions = listOf("to be honest", "at the end of the day")))
        assertTrue(text.contains("Common expressions: to be honest, at the end of the day"))
    }

    @Test
    fun `formatting is deterministic`() {
        val p = profile()
        assertEquals(formatStyleProfile(p), formatStyleProfile(p))
    }
}
