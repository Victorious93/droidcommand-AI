package ai.droidcommand.deviceconnect

import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DeviceConnectHandshakeTest {
    private val random = SecureRandom.getInstance("SHA1PRNG").also { it.setSeed(ByteArray(32) { 3 }) }

    @Test
    fun `both sides reach the same verification code`() {
        val password = "pair-me-now"
        val salt = Spake2.newSalt(random)
        val a = DeviceConnectHandshake(DeviceConnectHandshake.Role.INITIATOR, password, DeviceIdentityKeys.generate("aaaa", random), DeviceKind.PHONE, salt, random)
        val b = DeviceConnectHandshake(DeviceConnectHandshake.Role.RESPONDER, password, DeviceIdentityKeys.generate("bbbb", random), DeviceKind.PC, salt, random)
        val helloA = a.localHello()
        val helloB = b.localHello()
        assertIs<DeviceConnectDecision.Ok>(b.acceptPeer(helloA))
        assertIs<DeviceConnectDecision.Ok>(a.acceptPeer(helloB))
        assertEquals(a.verificationCode, b.verificationCode)
        assertTrue(a.verifyPeerMac(b.confirmMac()))
        assertTrue(b.verifyPeerMac(a.confirmMac()))
        val (_, channelA) = a.finishSession()
        val (_, channelB) = b.finishSession()
        val sealed = channelA.seal("ping".toByteArray())
        assertEquals("ping", channelB.open(sealed).decodeToString())
    }

    @Test
    fun `wrong password fails the confirm mac`() {
        val salt = Spake2.newSalt(random)
        val a = DeviceConnectHandshake(DeviceConnectHandshake.Role.INITIATOR, "pair-me-now", DeviceIdentityKeys.generate("aaaa", random), DeviceKind.PHONE, salt, random)
        val b = DeviceConnectHandshake(DeviceConnectHandshake.Role.RESPONDER, "different!", DeviceIdentityKeys.generate("bbbb", random), DeviceKind.PC, salt, random)
        a.localHello()
        b.localHello()
        assertIs<DeviceConnectDecision.Ok>(b.acceptPeer(a.localHello()))
        assertIs<DeviceConnectDecision.Ok>(a.acceptPeer(b.localHello()))
        assertFalse(a.verifyPeerMac(b.confirmMac()))
        assertFalse(b.verifyPeerMac(a.confirmMac()))
    }

    @Test
    fun `identity signatures bind the transcript`() {
        val password = "pair-me-now"
        val salt = Spake2.newSalt(random)
        val idA = DeviceIdentityKeys.generate("aaaa", random)
        val idB = DeviceIdentityKeys.generate("bbbb", random)
        val a = DeviceConnectHandshake(DeviceConnectHandshake.Role.INITIATOR, password, idA, DeviceKind.PHONE, salt, random)
        val b = DeviceConnectHandshake(DeviceConnectHandshake.Role.RESPONDER, password, idB, DeviceKind.PC, salt, random)
        a.localHello()
        b.localHello()
        a.acceptPeer(b.localHello())
        b.acceptPeer(a.localHello())
        val sigA = DeviceIdentityKeys.sign(idA, a.identityMessage())
        val sigB = DeviceIdentityKeys.sign(idB, b.identityMessage())
        assertTrue(DeviceIdentityKeys.verify(idA.publicHex, b.peerIdentityMessage(), sigA))
        assertTrue(DeviceIdentityKeys.verify(idB.publicHex, a.peerIdentityMessage(), sigB))
        assertFalse(DeviceIdentityKeys.verify(idA.publicHex, b.peerIdentityMessage(), sigB))
    }
}
