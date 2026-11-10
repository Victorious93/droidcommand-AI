package ai.droidcommand.remotepc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PcProfileLoaderTest {
    @Test
    fun `missing VictorSuite, missing provider and permission denied give plain messages`() {
        val notInstalled = assertIs<PcListState.Unavailable>(PcProfileLoader.evaluate(PcProfileSource.VictorSuiteNotInstalled))
        assertEquals(PcProfileLoader.NOT_INSTALLED, notInstalled.message)
        assertEquals(PcFixAction.INSTALL_OR_UPDATE_VICTORSUITE, notInstalled.action)

        val missing = assertIs<PcListState.Unavailable>(PcProfileLoader.evaluate(PcProfileSource.ProviderMissing))
        assertEquals(PcProfileLoader.PROVIDER_MISSING, missing.message)

        val denied = assertIs<PcListState.Unavailable>(PcProfileLoader.evaluate(PcProfileSource.PermissionDenied))
        assertEquals(PcProfileLoader.PERMISSION_DENIED, denied.message)

        val untrusted = assertIs<PcListState.Unavailable>(PcProfileLoader.evaluate(PcProfileSource.UntrustedPublisher("evil.app")))
        assertEquals(PcProfileLoader.UNTRUSTED, untrusted.message)

        val failed = assertIs<PcListState.Unavailable>(PcProfileLoader.evaluate(PcProfileSource.Failed("boom: stack trace")))
        assertFalse(failed.message.contains("boom"))
    }

    @Test
    fun `zero rows with consent required asks for consent`() {
        val state = PcProfileLoader.evaluate(PcProfileSource.Rows(emptyList(), PcProfileContract.CONSENT_REQUIRED))
        assertIs<PcListState.NeedsConsent>(state)
    }

    @Test
    fun `zero rows with consent granted is an empty ready list`() {
        val state = assertIs<PcListState.Ready>(PcProfileLoader.evaluate(PcProfileSource.Rows(emptyList(), PcProfileContract.CONSENT_GRANTED)))
        assertTrue(state.profiles.isEmpty())
    }

    @Test
    fun `rows parse into ready state`() {
        val state = assertIs<PcListState.Ready>(PcProfileLoader.evaluate(PcProfileSource.Rows(listOf(row(), row(PcProfileContract.Columns.PORT to "x")), null)))
        assertEquals(1, state.profiles.size)
        assertEquals(1, state.skippedRows)
    }

    @Test
    fun `provider trust is fail-closed`() {
        val pin = "67:17:C5:5A:BE:9E:0D:F8:C6:03:C9:A0:F3:F3:67:B6:CA:BD:B0:1F:3E:58:3A:4F:F6:44:E5:DF:3D:4C:31:D3"
        val pins = setOf(pin)
        assertTrue(PcProviderTrust.isTrusted("com.termux", listOf(pin), pins))
        assertTrue(PcProviderTrust.isTrusted("com.termux", listOf(pin.lowercase()), pins))
        assertFalse(PcProviderTrust.isTrusted("com.evil", listOf(pin), pins))
        assertFalse(PcProviderTrust.isTrusted("com.termux", emptyList(), pins))
        assertFalse(PcProviderTrust.isTrusted("com.termux", listOf(pin, pin), pins))
        assertFalse(PcProviderTrust.isTrusted("com.termux", listOf(pin.replace("67", "00")), pins))
        assertFalse(PcProviderTrust.isTrusted("com.termux", listOf(pin), emptySet()))
        assertFalse(PcProviderTrust.isTrusted("com.termux", listOf(pin), setOf("TBD")))
    }
}
