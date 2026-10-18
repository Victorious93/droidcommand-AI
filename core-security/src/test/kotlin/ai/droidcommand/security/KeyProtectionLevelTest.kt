package ai.droidcommand.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KeyProtectionLevelTest {
    @Test
    fun `android security level constants are pinned to the platform values`() {
        // android.security.keystore.KeyProperties: UNKNOWN=-1, UNKNOWN_SECURE=-2, SOFTWARE=0, TRUSTED_ENVIRONMENT=1, STRONGBOX=2
        assertEquals(-1, KeyProtectionLevel.ANDROID_SECURITY_LEVEL_UNKNOWN)
        assertEquals(-2, KeyProtectionLevel.ANDROID_SECURITY_LEVEL_UNKNOWN_SECURE)
        assertEquals(0, KeyProtectionLevel.ANDROID_SECURITY_LEVEL_SOFTWARE)
        assertEquals(1, KeyProtectionLevel.ANDROID_SECURITY_LEVEL_TRUSTED_ENVIRONMENT)
        assertEquals(2, KeyProtectionLevel.ANDROID_SECURITY_LEVEL_STRONGBOX)
    }

    @Test
    fun `maps each android security level`() {
        assertEquals(KeyProtectionLevel.STRONGBOX, KeyProtectionLevel.fromAndroidSecurityLevel(2))
        assertEquals(KeyProtectionLevel.TRUSTED_ENVIRONMENT, KeyProtectionLevel.fromAndroidSecurityLevel(1))
        assertEquals(KeyProtectionLevel.SOFTWARE, KeyProtectionLevel.fromAndroidSecurityLevel(0))
    }

    @Test
    fun `unknown-secure is reported as the weaker hardware level and other values as UNKNOWN`() {
        assertEquals(KeyProtectionLevel.TRUSTED_ENVIRONMENT, KeyProtectionLevel.fromAndroidSecurityLevel(-2))
        assertEquals(KeyProtectionLevel.UNKNOWN, KeyProtectionLevel.fromAndroidSecurityLevel(-1))
        assertEquals(KeyProtectionLevel.UNKNOWN, KeyProtectionLevel.fromAndroidSecurityLevel(99))
        assertEquals(KeyProtectionLevel.UNKNOWN, KeyProtectionLevel.fromAndroidSecurityLevel(Int.MIN_VALUE))
    }

    @Test
    fun `legacy boolean never claims StrongBox`() {
        assertEquals(KeyProtectionLevel.TRUSTED_ENVIRONMENT, KeyProtectionLevel.fromInsideSecureHardware(true))
        assertEquals(KeyProtectionLevel.SOFTWARE, KeyProtectionLevel.fromInsideSecureHardware(false))
    }

    @Test
    fun `only strongbox and tee count as hardware backed`() {
        assertTrue(KeyProtectionLevel.STRONGBOX.isHardwareBacked)
        assertTrue(KeyProtectionLevel.TRUSTED_ENVIRONMENT.isHardwareBacked)
        assertFalse(KeyProtectionLevel.SOFTWARE.isHardwareBacked)
        assertFalse(KeyProtectionLevel.UNKNOWN.isHardwareBacked)
    }
}
