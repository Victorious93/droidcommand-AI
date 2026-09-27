package ai.droidcommand.remote.pairing

import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** A record failed to open: wrong key, tampered bytes, a replayed or reordered counter, or a malformed frame. */
class SecureChannelException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * One side's view of a paired session: a key for what it sends and a
 * different key for what it receives. Produced by [PairingHandshake]; the
 * controller's [sendKey] is the device's [receiveKey] and vice versa.
 */
class SessionKeys(sendKey: ByteArray, receiveKey: ByteArray) {
    internal val sendKey: ByteArray = sendKey.copyOf()
    internal val receiveKey: ByteArray = receiveKey.copyOf()

    init {
        require(sendKey.size == KEY_BYTES && receiveKey.size == KEY_BYTES) { "Session keys must be $KEY_BYTES bytes (AES-256)" }
        require(!sendKey.contentEquals(receiveKey)) { "Send and receive keys must differ" }
    }

    override fun toString(): String = "SessionKeys(<redacted>)"

    companion object {
        const val KEY_BYTES = 32
    }
}

/**
 * AES-256-GCM record encryption over a paired session.
 *
 * Each record is `counter (8 bytes, big-endian) || ciphertext || tag (16 bytes)`.
 * The 96-bit GCM nonce is four zero bytes followed by the counter, and the
 * counter bytes are also the record's associated data, so a counter can't be
 * swapped without failing authentication. Because the two directions use
 * different keys, both sides may start their counters at zero without ever
 * reusing a (key, nonce) pair.
 *
 * The receiver requires counters to strictly increase: a replayed, duplicated
 * or reordered record is rejected before decryption, and the receive counter
 * only advances after a record authenticates, so a forged record can't push
 * it forward and block real traffic. This suits an in-order transport (a TCP
 * stream, a WebSocket); it deliberately does not tolerate reordering.
 *
 * Not thread-safe: use one instance per connection and serialize [seal] and
 * [open] calls per direction. [close] may be called from any thread; after
 * it, [seal] and [open] throw [SecureChannelException]. [PairingRegistry]
 * closes a peer's channels when that peer is revoked.
 */
class SecureChannel(private val keys: SessionKeys) {
    private var nextSendCounter = 0L
    private var lastReceivedCounter = -1L

    @Volatile
    var isClosed: Boolean = false
        private set

    private val closeListeners = mutableListOf<() -> Unit>()

    /** Closes the channel and runs every [onClose] listener once. Idempotent. */
    fun close() {
        val listeners = synchronized(closeListeners) {
            if (isClosed) return
            isClosed = true
            closeListeners.toList().also { closeListeners.clear() }
        }
        listeners.forEach { it() }
    }

    /** Runs [action] when the channel is closed, or at once if it already is. */
    fun onClose(action: () -> Unit) {
        synchronized(closeListeners) {
            if (!isClosed) {
                closeListeners += action
                return
            }
        }
        action()
    }

    fun seal(plaintext: ByteArray): ByteArray {
        if (isClosed) throw SecureChannelException("Channel is closed")
        check(nextSendCounter != Long.MAX_VALUE) { "Send counter exhausted; re-pair to get fresh keys" }
        val counter = nextSendCounter++
        val header = counterBytes(counter)
        val cipher = cipher(Cipher.ENCRYPT_MODE, keys.sendKey, counter)
        cipher.updateAAD(header)
        return header + cipher.doFinal(plaintext)
    }

    fun open(record: ByteArray): ByteArray {
        if (isClosed) throw SecureChannelException("Channel is closed")
        if (record.size < COUNTER_BYTES + TAG_BYTES) {
            throw SecureChannelException("Record too short: ${record.size} bytes")
        }
        val header = record.copyOfRange(0, COUNTER_BYTES)
        val counter = ByteBuffer.wrap(header).long
        if (counter < 0 || counter <= lastReceivedCounter) {
            throw SecureChannelException("Rejected replayed or out-of-order record (counter $counter, last accepted $lastReceivedCounter)")
        }
        val plaintext = try {
            val cipher = cipher(Cipher.DECRYPT_MODE, keys.receiveKey, counter)
            cipher.updateAAD(header)
            cipher.doFinal(record, COUNTER_BYTES, record.size - COUNTER_BYTES)
        } catch (e: GeneralSecurityException) {
            throw SecureChannelException("Record failed authentication", e)
        }
        lastReceivedCounter = counter
        return plaintext
    }

    private fun cipher(mode: Int, key: ByteArray, counter: Long): Cipher {
        val nonce = ByteArray(NONCE_BYTES)
        counterBytes(counter).copyInto(nonce, NONCE_BYTES - COUNTER_BYTES)
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BYTES * 8, nonce))
        }
    }

    private fun counterBytes(counter: Long): ByteArray = ByteBuffer.allocate(COUNTER_BYTES).putLong(counter).array()

    companion object {
        const val COUNTER_BYTES = 8
        const val NONCE_BYTES = 12
        const val TAG_BYTES = 16
    }
}
