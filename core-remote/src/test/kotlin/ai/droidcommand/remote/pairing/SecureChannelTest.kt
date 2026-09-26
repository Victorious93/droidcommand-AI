package ai.droidcommand.remote.pairing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class SecureChannelTest {
    private val a = ByteArray(32) { 1 }
    private val b = ByteArray(32) { 2 }
    private fun controller() = SecureChannel(SessionKeys(sendKey = a, receiveKey = b))
    private fun device() = SecureChannel(SessionKeys(sendKey = b, receiveKey = a))

    @Test
    fun `records carry an increasing counter and encrypt the payload`() {
        val sender = controller()
        val first = sender.seal("hello".toByteArray())
        val second = sender.seal("hello".toByteArray())
        assertEquals(SecureChannel.COUNTER_BYTES + 5 + SecureChannel.TAG_BYTES, first.size)
        assertNotEquals(first.toList(), second.toList())
        assertEquals(0L, java.nio.ByteBuffer.wrap(first, 0, 8).long)
        assertEquals(1L, java.nio.ByteBuffer.wrap(second, 0, 8).long)
    }

    @Test
    fun `a replayed record is rejected`() {
        val sender = controller()
        val receiver = device()
        val record = sender.seal("reboot".toByteArray())
        receiver.open(record)
        assertFailsWith<SecureChannelException> { receiver.open(record) }
    }

    @Test
    fun `an older record arriving after a newer one is rejected`() {
        val sender = controller()
        val receiver = device()
        val older = sender.seal("1".toByteArray())
        val newer = sender.seal("2".toByteArray())
        receiver.open(newer)
        assertFailsWith<SecureChannelException> { receiver.open(older) }
    }

    @Test
    fun `a tampered record fails authentication and does not advance the counter`() {
        val sender = controller()
        val receiver = device()
        val record = sender.seal("rm -rf /sdcard/tmp".toByteArray())
        val tampered = record.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertFailsWith<SecureChannelException> { receiver.open(tampered) }
        assertEquals("rm -rf /sdcard/tmp", receiver.open(record).decodeToString())
    }

    @Test
    fun `a record whose counter header was altered fails authentication`() {
        val sender = controller()
        val receiver = device()
        val record = sender.seal("x".toByteArray())
        record[7] = 5
        assertFailsWith<SecureChannelException> { receiver.open(record) }
    }

    @Test
    fun `a record sealed for the other direction cannot be opened as incoming`() {
        val sender = controller()
        val record = sender.seal("x".toByteArray())
        // The controller's own receive key is the device's send key, not its own send key.
        assertFailsWith<SecureChannelException> { controller().open(record) }
    }

    @Test
    fun `a truncated record is rejected`() {
        assertFailsWith<SecureChannelException> { device().open(ByteArray(10)) }
    }

    @Test
    fun `session keys must be AES-256 sized and distinct per direction`() {
        assertFailsWith<IllegalArgumentException> { SessionKeys(ByteArray(16), b) }
        assertFailsWith<IllegalArgumentException> { SessionKeys(a, a.copyOf()) }
    }
}
