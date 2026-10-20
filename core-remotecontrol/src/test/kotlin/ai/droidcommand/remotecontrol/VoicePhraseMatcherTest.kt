package ai.droidcommand.remotecontrol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class VoicePhraseMatcherTest {
    @Test
    fun `normalize strips punctuation and case`() {
        assertEquals("lock the tablet", VoicePhraseMatcher.normalize("  Lock, the TABLET! "))
    }

    @Test
    fun `exact match after normalize`() {
        val mappings = listOf(mapping(id = "a", voicePhrase = "Lock the tablet", smsCommandId = "lock"))
        val hit = assertIs<VoiceMatch.Hit>(VoicePhraseMatcher.match("LOCK THE TABLET", mappings))
        assertEquals("a", hit.mapping.id)
    }

    @Test
    fun `unmatched and empty transcripts are none`() {
        val mappings = listOf(mapping(voicePhrase = "hello there"))
        assertEquals(VoiceMatch.None, VoicePhraseMatcher.match("goodbye", mappings))
        assertEquals(VoiceMatch.None, VoicePhraseMatcher.match("   ", mappings))
        assertEquals(VoiceMatch.None, VoicePhraseMatcher.match("hello", emptyList()))
    }

    @Test
    fun `two mappings with the same phrase are ambiguous`() {
        val mappings = listOf(
            mapping(id = "a", voicePhrase = "do it", smsCommandId = "a"),
            mapping(id = "b", voicePhrase = "DO IT!", smsCommandId = "b"),
        )
        val result = assertIs<VoiceMatch.Ambiguous>(VoicePhraseMatcher.match("do it", mappings))
        assertEquals(listOf("a", "b"), result.mappingIds)
    }
}
