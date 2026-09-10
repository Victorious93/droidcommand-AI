package ai.droidcommand.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private fun testPersona(enabled: Boolean) = Persona(
    id = "p1",
    name = "Alex",
    category = PersonaCategory.PERSONAL,
    sourceConversations = listOf("conv-1"),
    styleCharacteristics = StyleProfile(
        tone = "friendly",
        vocabulary = VocabProfile("simple"),
        sentenceStructure = StructureProfile("short", "casual"),
        formality = Formality.CASUAL,
        verbosity = Verbosity.CONCISE,
        humor = HumorProfile(present = false),
        responseStructure = "direct",
    ),
    contextContribution = "Tone: friendly. Formality: CASUAL.",
    version = "1",
    enabled = enabled,
)

class PersonaContextProviderTest {
    @Test
    fun `an enabled persona contributes its contextContribution under ContextKind PERSONA`() {
        val persona = testPersona(enabled = true)
        val provider = PersonaContextProvider { persona }

        val contribution = provider.provide(null)!!

        assertEquals(ContextKind.PERSONA, contribution.kind)
        assertEquals(persona.contextContribution, contribution.content)
    }

    @Test
    fun `a disabled persona contributes nothing`() {
        val provider = PersonaContextProvider { testPersona(enabled = false) }
        assertNull(provider.provide(null))
    }

    @Test
    fun `no active persona contributes nothing`() {
        val provider = PersonaContextProvider { null }
        assertNull(provider.provide(null))
    }
}
