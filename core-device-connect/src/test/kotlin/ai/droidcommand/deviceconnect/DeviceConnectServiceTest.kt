package ai.droidcommand.deviceconnect

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DeviceConnectServiceTest {
    private fun service(): DeviceConnectService = DeviceConnectService(InMemoryDeviceConnectStore())

    @Test
    fun `pairing starts idle and off`() {
        val svc = service()
        assertEquals(DeviceConnectPhase.IDLE, svc.phase)
        assertFalse(svc.keepListening)
        assertTrue(svc.peers().isEmpty())
    }

    @Test
    fun `generated password is ten unambiguous characters`() {
        val password = service().generatePassword()
        assertEquals(DeviceConnect.GENERATED_PASSWORD_CHARS, password.length)
        assertTrue(password.all { it in "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" })
    }

    @Test
    fun `five failures lock the peer`() {
        val svc = service()
        repeat(DeviceConnect.MAX_FAILURES - 1) { i ->
            val refused = svc.failAttempt("192.0.2.10", "The password did not match.")
            assertIs<DeviceConnectDecision.Refused>(refused)
            assertFalse(refused.locked)
        }
        val locked = svc.failAttempt("192.0.2.10", "The password did not match.")
        assertIs<DeviceConnectDecision.Refused>(locked)
        assertTrue(locked.locked)
        val again = svc.lockoutDecision("192.0.2.10")
        assertIs<DeviceConnectDecision.Refused>(again)
        assertTrue(again.locked)
    }

    @Test
    fun `tofu refuses a changed device key and revoke clears it`() {
        val svc = service()
        val first = svc.link("abc", "Phone abc", DeviceKind.PHONE, "aa".repeat(32))
        assertIs<DeviceConnectDecision.Ok>(first)
        val conflict = svc.link("abc", "Phone abc", DeviceKind.PHONE, "bb".repeat(32))
        assertIs<DeviceConnectDecision.Refused>(conflict)
        assertTrue(svc.revoke("abc"))
        val again = svc.link("abc", "Phone abc", DeviceKind.PHONE, "bb".repeat(32))
        assertIs<DeviceConnectDecision.Ok>(again)
    }

    @Test
    fun `same tofu key can reconnect`() {
        val svc = service()
        svc.link("abc", "Phone abc", DeviceKind.PHONE, "aa".repeat(32))
        val accepted = svc.acceptLinked("abc", "AA".repeat(32))
        assertIs<DeviceConnectDecision.Ok>(accepted)
    }

    @Test
    fun `chat notes land in the inbox and control is not implied`() {
        val svc = service()
        val posted = svc.postChat("abc", "Phone abc", "hello from the other phone")
        assertIs<DeviceConnectDecision.Ok>(posted)
        val notes = svc.inbox().drain()
        assertEquals(1, notes.size)
        assertEquals("hello from the other phone", notes.single().text)
        assertTrue(svc.inbox().drain().isEmpty())
        val tooLong = svc.postChat("abc", "Phone abc", "x".repeat(DeviceConnect.MAX_CHAT_CHARS + 1))
        assertIs<DeviceConnectDecision.Refused>(tooLong)
    }

    @Test
    fun `keep listening is off until the owner turns it on`() {
        val store = InMemoryDeviceConnectStore()
        val svc = DeviceConnectService(store)
        assertFalse(svc.keepListening)
        svc.setKeepListening(true)
        assertTrue(store.load().keepListening)
        val reloaded = DeviceConnectService(store)
        assertTrue(reloaded.keepListening)
    }

    @Test
    fun `snapshot codec hides nothing required and drops a bad kind`() {
        val identity = DeviceIdentityKeys.generate()
        val snapshot = DeviceConnectSnapshot(
            keepListening = true,
            identity = identity,
            peers = listOf(TrustedDevice("abc", "Computer abc", DeviceKind.PC, "aa".repeat(32), 1L)),
        )
        val encoded = DeviceConnectCodec.encode(snapshot)
        val decoded = DeviceConnectCodec.decode(encoded)
        assertEquals(true, decoded?.keepListening)
        assertEquals(identity.deviceId, decoded?.identity?.deviceId)
        assertEquals(1, decoded?.peers?.size)
        assertEquals(null, DeviceConnectCodec.decode("{not-json"))
    }

    @Test
    fun `invite parse accepts a host and port`() {
        val invite = DeviceConnect.inviteOf("192.168.1.8", 17890, "abcd")
        assertEquals(Triple("192.168.1.8", 17890, "abcd"), DeviceConnect.parseInvite(invite))
        assertEquals(null, DeviceConnect.parseInvite("nope"))
    }
}
