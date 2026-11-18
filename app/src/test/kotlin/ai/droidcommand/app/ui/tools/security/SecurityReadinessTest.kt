package ai.droidcommand.app.ui.tools.security

import ai.droidcommand.app.ui.components.PillTone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecurityReadinessTest {
    private fun ready(suite: Boolean, granted: Boolean, set: Boolean) = SecurityReadiness(suite, granted, set)

    @Test
    fun `metasploit needs both the app and the permission`() {
        assertTrue(ready(true, true, false).metasploitUsable)
        assertFalse(ready(true, false, false).metasploitUsable)
        assertFalse(ready(false, true, false).metasploitUsable)
        assertFalse(ready(false, false, false).metasploitUsable)
    }

    @Test
    fun `a missing app is reported before the permission it would carry`() {
        assertTrue(ready(false, false, false).permissionDetail().contains("first"))
        assertEquals("Granted.", ready(true, true, false).permissionDetail())
        assertTrue(ready(true, false, false).permissionDetail().contains("Not granted"))
    }

    @Test
    fun `a ready setup is still never called working`() {
        val (text, tone) = ready(true, true, true).metasploitPill()
        assertEquals("Not verified on a device", text)
        assertEquals(PillTone.Neutral, tone)
        assertFalse(text.contains("Ready", ignoreCase = true))
    }

    @Test
    fun `a setup with something missing asks for setup, for either tool`() {
        assertEquals("Needs setup" to PillTone.Warning, ready(false, false, false).metasploitPill())
        assertEquals("Needs setup" to PillTone.Warning, ready(true, false, false).metasploitPill())
        assertEquals("Needs setup" to PillTone.Warning, ready(true, true, false).setPill())
    }

    @Test
    fun `SET and Metasploit read the same pill once SET's own backend answers available`() {
        assertEquals(ready(true, true, true).metasploitPill(), ready(true, true, true).setPill())
    }

    @Test
    fun `SET detail explains it shares Metasploit's VictorSuite precondition, not a second install`() {
        assertTrue(ready(true, true, true).setDetail().contains("same"))
        assertTrue(ready(false, false, false).setDetail().contains("same"))
    }
}
