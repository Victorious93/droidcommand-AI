package ai.droidcommand.deviceconnect

import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class Spake2Test {
    @Test
    fun `same password yields the same shared secret on both sides`() {
        val random = SecureRandom.getInstance("SHA1PRNG").also { it.setSeed(ByteArray(16) { 7 }) }
        val salt = Spake2.newSalt(random)
        val initiator = Spake2.Initiator("correct-horse", salt, random)
        val responder = Spake2.Responder("correct-horse", salt, random)
        val x = initiator.share()
        val y = responder.share()
        val left = initiator.finish(y)
        val right = responder.finish(x)
        assertTrue(left.contentEquals(right))
        assertEquals(Spake2.verificationCode(left), Spake2.verificationCode(right))
        val (send, recv) = Spake2.sessionKeys(left, initiatorToResponder = true)
        val (peerSend, peerRecv) = Spake2.sessionKeys(right, initiatorToResponder = false)
        assertTrue(send.contentEquals(peerRecv))
        assertTrue(recv.contentEquals(peerSend))
        assertFalse(send.contentEquals(recv))
    }

    @Test
    fun `wrong password does not produce the same secret`() {
        val random = SecureRandom.getInstance("SHA1PRNG").also { it.setSeed(ByteArray(16) { 9 }) }
        val salt = Spake2.newSalt(random)
        val initiator = Spake2.Initiator("correct-horse", salt, random)
        val responder = Spake2.Responder("wrong-password", salt, random)
        val x = initiator.share()
        val y = responder.share()
        val left = initiator.finish(y)
        val right = responder.finish(x)
        assertFalse(left.contentEquals(right))
        val transcript = "demo".toByteArray()
        val mac = Spake2.confirmMac(left, "initiator", transcript)
        val expected = Spake2.confirmMac(right, "initiator", transcript)
        assertFalse(Spake2.macsEqual(mac, expected))
    }

    @Test
    fun `verification codes are six digits`() {
        val code = Spake2.verificationCode(ByteArray(32) { 1 })
        assertTrue(code.matches(Regex("\\d{3}-\\d{3}")), code)
        assertNotEquals("000-000", code)
    }
}
