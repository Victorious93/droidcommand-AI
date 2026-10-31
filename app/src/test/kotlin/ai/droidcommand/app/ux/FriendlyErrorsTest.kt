package ai.droidcommand.app.ux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FriendlyErrorsTest {
    @Test
    fun `empty raw becomes a generic line`() {
        assertEquals("Something went wrong. Try again.", FriendlyErrors.forUser(null))
        assertEquals("Something went wrong. Try again.", FriendlyErrors.forUser("  "))
    }

    @Test
    fun `maps missing key and model language`() {
        val key = FriendlyErrors.forUser("No API key for Groq. Send is refused until you add one.")
        assertTrue(key.contains("Settings"), key)
        assertTrue(key.contains("Models"), key)
        assertFalse(key.contains("refused"), key)

        val model = FriendlyErrors.forUser("No on-device model is selected.")
        assertTrue(model.contains("Models"), model)
    }

    @Test
    fun `maps fleet blocks without internal words`() {
        val same = FriendlyErrors.forUser("Bind refused (same-signing)")
        assertTrue(same.startsWith("Blocked"), same)
        assertFalse(same.contains("AIDL"), same)
        assertFalse(same.contains("pin"), same)

        val cert = FriendlyErrors.forUser("Cert mismatch — installed signer is not the paired certificate.")
        assertTrue(cert.contains("does not match"), cert)

        val peer = FriendlyErrors.forUser("Peer control off — external control is disabled")
        assertTrue(peer.contains("remote control"), peer)
    }

    @Test
    fun `unknown text is kept`() {
        assertEquals("file was empty", FriendlyErrors.forUser("file was empty"))
    }

    @Test
    fun `chat empty copy never says refused`() {
        assertFalse(FriendlyErrors.emptyChat(false).contains("refuse"))
        assertFalse(FriendlyErrors.missingChatSetup("Groq").contains("refuse"))
    }
}
