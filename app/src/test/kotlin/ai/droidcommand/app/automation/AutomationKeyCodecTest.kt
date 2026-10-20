package ai.droidcommand.app.automation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class AutomationKeyCodecTest {
    private val shell = AutomationKey("0123abcd", "List\tfiles\nhere", AutomationAction.Shell("/system/bin/ls", "-la\t/sdcard"))
    private val ai = AutomationKey("fedcba98", "Ask Claude", AutomationAction.Ai("anthropic", "Line one\nline two\t100% + more"))

    @Test
    fun `round-trips shell and AI keys, including tabs, newlines and percent signs`() {
        assertEquals(shell, AutomationKeyCodec.decode(AutomationKeyCodec.encode(shell)))
        assertEquals(ai, AutomationKeyCodec.decode(AutomationKeyCodec.encode(ai)))
    }

    @Test
    fun `an encoded key is a single line`() {
        assertFalse(AutomationKeyCodec.encode(ai).contains('\n'))
        assertFalse(AutomationKeyCodec.encode(shell).contains('\n'))
    }

    @Test
    fun `lines that are not complete valid records decode to null`() {
        assertNull(AutomationKeyCodec.decode(""))
        assertNull(AutomationKeyCodec.decode("only\tthree\tfields"))
        assertNull(AutomationKeyCodec.decode(listOf("id", "label", "webhook", "x", "y").joinToString("\t")))
        assertNull(AutomationKeyCodec.decode(listOf("id", "label", "ai", "nosuch", "prompt").joinToString("\t")))
        assertNull(AutomationKeyCodec.decode(listOf("id", "label", "ai", "anthropic", "").joinToString("\t")))
        assertNull(AutomationKeyCodec.decode(listOf("id", "label", "shell", "", "-la").joinToString("\t")))
        assertNull(AutomationKeyCodec.decode(listOf("", "label", "shell", "ls", "").joinToString("\t")))
        assertNull(AutomationKeyCodec.decode(listOf("id", "label", "shell", "%ZZ", "").joinToString("\t")))
    }

    @Test
    fun `a shell key may have no arguments`() {
        val bare = AutomationKey("id1", "Uptime", AutomationAction.Shell("uptime", ""))
        assertEquals(bare, AutomationKeyCodec.decode(AutomationKeyCodec.encode(bare)))
    }
}
