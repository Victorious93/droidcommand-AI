package ai.droidcommand.config

import ai.droidcommand.security.PasswordPairing
import ai.droidcommand.security.PasswordPairingDecision
import ai.droidcommand.security.PasswordPairingService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VaultPasswordPairingStoreTest {
    @Test
    fun `snapshot survives a vault round-trip and never stores the plaintext password`() {
        val vault = InMemorySecretsVault()
        val store = VaultPasswordPairingStore(vault)
        val first = PasswordPairingService(store)
        first.setPassword("correct-horse")
        first.enable(true)
        val allowed = assertIs<PasswordPairingDecision.Allowed>(
            first.confirmWithPassword(
                "com.victorious.sms",
                "aa".repeat(32),
                "correct-horse",
                screenConfirmed = true,
            ),
        )
        assertTrue(allowed.newlyLinked)
        val raw = vault.getSecret(PasswordPairing.VAULT_SECRET_ID)
        assertTrue(raw != null && !raw.contains("correct-horse"))
        val reloaded = PasswordPairingService(VaultPasswordPairingStore(vault))
        assertTrue(reloaded.isEnabled())
        assertTrue(reloaded.hasPassword())
        assertEquals("com.victorious.sms", reloaded.linkedPackages().single().packageName)
        assertFalse(reloaded.isLinked("com.victorious.sms", "bb".repeat(32)))
    }
}
