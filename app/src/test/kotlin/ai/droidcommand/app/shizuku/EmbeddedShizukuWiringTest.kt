package ai.droidcommand.app.shizuku

import ai.droidcommand.app.ux.ScreenHelpCatalog
import ai.droidcommand.shizuku.EmbeddedShizukuCopy
import ai.droidcommand.shizuku.EmbeddedShizukuPrefs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmbeddedShizukuWiringTest {
    @Test
    fun `start on boot stays off until the user turns it on`() {
        assertFalse(EmbeddedShizukuPrefs.DEFAULT_START_ON_BOOT)
        assertEquals("shizuku.start_on_boot", EmbeddedShizukuPrefs.KEY_START_ON_BOOT)
        assertTrue(EmbeddedShizukuCopy.START_ON_BOOT_DEFAULT_OFF.startsWith("Off until you turn it on"))
    }

    @Test
    fun `the one-time grant command names this app id`() {
        assertEquals(
            "adb shell pm grant ai.droidcommand.app android.permission.WRITE_SECURE_SETTINGS",
            EmbeddedShizukuCopy.WRITE_SECURE_SETTINGS_COMMAND,
        )
    }

    @Test
    fun `Advanced help names built-in Shizuku and keeps start-on-boot off`() {
        assertTrue(ScreenHelpCatalog.ADVANCED.body.contains("built-in Shizuku"))
        assertTrue(ScreenHelpCatalog.ADVANCED.body.contains("stays off"))
    }
}
