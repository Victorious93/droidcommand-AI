package ai.droidcommand.app.ui.tools.security

import ai.droidcommand.app.ui.components.PillTone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecurityReadinessTest {
    private fun ready(suite: Boolean, granted: Boolean, set: Boolean = false) = SecurityReadiness(suite, granted, set)

    @Test
    fun `metasploit needs both the app and the permission`() {
        assertTrue(ready(true, true).metasploitUsable)
        assertFalse(ready(true, false).metasploitUsable)
        assertFalse(ready(false, true).metasploitUsable)
        assertFalse(ready(false, false).metasploitUsable)
    }

    @Test
    fun `a missing app is reported before the permission it would carry`() {
        assertTrue(ready(false, false).permissionDetail().contains("first"))
        assertEquals("Granted.", ready(true, true).permissionDetail())
        assertTrue(ready(true, false).permissionDetail().contains("Not granted"))
    }

    @Test
    fun `a ready setup is still never called working`() {
        val (text, tone) = ready(true, true).metasploitPill()
        assertEquals("Not verified on a device", text)
        assertEquals(PillTone.Neutral, tone)
        assertFalse(text.contains("Ready", ignoreCase = true))
    }

    @Test
    fun `a setup with something missing asks for setup`() {
        assertEquals("Needs setup" to PillTone.Warning, ready(false, false).metasploitPill())
        assertEquals("Needs setup" to PillTone.Warning, ready(true, false).metasploitPill())
    }

    @Test
    fun `SET with no backend says it is not built, and with one says it is unverified`() {
        assertEquals("Not built yet" to PillTone.Error, ready(true, true, set = false).setPill())
        assertEquals("Not verified on a device", ready(true, true, set = true).setPill().first)
        assertTrue(ready(true, true, set = false).setDetail().contains("cannot launch"))
    }
}
