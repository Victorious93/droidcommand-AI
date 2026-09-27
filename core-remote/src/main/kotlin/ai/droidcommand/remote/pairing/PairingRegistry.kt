package ai.droidcommand.remote.pairing

import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

enum class PairingStatus { ACTIVE, EXPIRED, REVOKED }

/** A controller this device has paired with. Never holds the secret itself. */
data class PairedDevice(
    val id: String,
    val displayName: String,
    val pairedAt: Instant,
    val expiresAt: Instant? = null,
    val revokedAt: Instant? = null,
) {
    fun status(now: Instant): PairingStatus = when {
        revokedAt != null -> PairingStatus.REVOKED
        expiresAt != null && !now.isBefore(expiresAt) -> PairingStatus.EXPIRED
        else -> PairingStatus.ACTIVE
    }
}

/**
 * The result of [PairingRegistry.pair]: the new record and its secret. The
 * secret is returned only here, to be shown to the user once (a QR code or
 * typed code) and handed to the controller out of band.
 */
class NewPairing(val device: PairedDevice, val secret: PairingSecret) {
    override fun toString(): String = "NewPairing(device=$device, secret=<redacted>)"
}

/**
 * The device side's list of paired controllers (ROADMAP-101): pair a new
 * controller, list them, revoke one, and let pairings expire.
 *
 * [secretFor] is what [PairingHandshake.Device] consults, so a revoked or
 * expired controller can't start or finish a handshake. Channels opened
 * through [openChannel] are tracked per controller: [revoke] closes them at
 * once, and [closeExpiredChannels] closes those whose pairing has since
 * expired (call it periodically; nothing here runs a timer).
 *
 * In memory unless a [store] is given. With one, saved pairings are loaded
 * at construction and every [pair] and [revoke] is saved before it returns,
 * so pairings, expiry times and revocations survive a restart. The JVM `cli`
 * uses [FilePairingStore] (an owner-only file, as the project owner chose on
 * 2026-09-27); on the Android app, pairings belong in Android Keystore,
 * which the still-PLANNED `:app` module would provide. Thread-safe.
 */
class PairingRegistry(
    private val clock: Clock = Clock.systemUTC(),
    private val random: SecureRandom = SecureRandom(),
    private val store: PairingStore? = null,
) {
    private class Entry(var device: PairedDevice, val secret: PairingSecret) {
        val channels = mutableListOf<SecureChannel>()
    }

    private val entries = linkedMapOf<String, Entry>()

    init {
        store?.load()?.forEach { entries[it.device.id] = Entry(it.device, it.secret) }
    }

    private fun persist() {
        store?.save(entries.values.map { StoredPairing(it.device, it.secret) })
    }

    /** Pairs a new controller. With [ttl], the pairing expires that long after now. */
    @Synchronized
    fun pair(displayName: String, ttl: Duration? = null): NewPairing {
        require(displayName.isNotBlank()) { "displayName must not be blank" }
        require(ttl == null || (!ttl.isNegative && !ttl.isZero)) { "ttl must be positive" }
        val now = clock.instant()
        val device = PairedDevice(
            id = UUID.randomUUID().toString(),
            displayName = displayName.trim(),
            pairedAt = now,
            expiresAt = ttl?.let { now.plus(it) },
        )
        val secret = PairingSecret.generate(random)
        entries[device.id] = Entry(device, secret)
        try {
            persist()
        } catch (e: java.io.IOException) {
            // A pairing that can't be saved would vanish on restart; don't hand out its secret.
            entries.remove(device.id)
            throw e
        }
        return NewPairing(device, secret)
    }

    /** Every pairing ever made here, including revoked and expired ones, oldest first. */
    @Synchronized
    fun list(): List<PairedDevice> = entries.values.map { it.device }

    @Synchronized
    fun get(id: String): PairedDevice? = entries[id]?.device

    @Synchronized
    fun status(id: String): PairingStatus? = entries[id]?.device?.status(clock.instant())

    /** The secret for [id] while its pairing is active; null when unknown, revoked or expired. */
    @Synchronized
    fun secretFor(id: String): PairingSecret? {
        val entry = entries[id] ?: return null
        return if (entry.device.status(clock.instant()) == PairingStatus.ACTIVE) entry.secret else null
    }

    /**
     * Revokes [id] and closes every channel opened for it. Returns false when
     * [id] is unknown or already revoked. The record stays in [list] as revoked.
     * With a [store], the revocation takes effect in memory first and is then
     * saved; if saving fails this still revokes, then throws the save error.
     */
    @Synchronized
    fun revoke(id: String): Boolean {
        val entry = entries[id] ?: return false
        if (entry.device.revokedAt != null) return false
        entry.device = entry.device.copy(revokedAt = clock.instant())
        entry.channels.forEach(SecureChannel::close)
        entry.channels.clear()
        persist()
        return true
    }

    /**
     * Wraps [keys] from a completed handshake with [id] in a [SecureChannel]
     * that [revoke] can close. Throws [IllegalStateException] when the
     * pairing is no longer active, so a handshake that raced a revocation
     * never yields a usable channel.
     */
    @Synchronized
    fun openChannel(id: String, keys: SessionKeys): SecureChannel {
        val entry = entries[id] ?: throw IllegalStateException("No pairing with id $id")
        val status = entry.device.status(clock.instant())
        check(status == PairingStatus.ACTIVE) { "Pairing $id is $status; cannot open a channel" }
        entry.channels.removeAll { it.isClosed }
        return SecureChannel(keys).also { entry.channels += it }
    }

    /** Open channels tracked for [id]. */
    @Synchronized
    fun openChannelCount(id: String): Int = entries[id]?.channels?.count { !it.isClosed } ?: 0

    /** Closes the channels of every pairing that has expired. Returns the ids whose channels were closed. */
    @Synchronized
    fun closeExpiredChannels(): List<String> {
        val now = clock.instant()
        return entries.values
            .filter { it.device.status(now) == PairingStatus.EXPIRED && it.channels.any { channel -> !channel.isClosed } }
            .map { entry ->
                entry.channels.forEach(SecureChannel::close)
                entry.channels.clear()
                entry.device.id
            }
    }
}
