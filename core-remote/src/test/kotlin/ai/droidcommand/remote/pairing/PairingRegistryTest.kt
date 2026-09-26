package ai.droidcommand.remote.pairing

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairingRegistryTest {
    private val clock = MutableClock(Instant.parse("2026-09-26T12:00:00Z"))
    private val registry = PairingRegistry(clock)
    private val device = PairingHandshake.Device(registry::secretFor)

    /** Runs a full handshake as controller [id] holding [secret]; returns the device side's result. */
    private fun handshake(id: String, secret: PairingSecret): Any {
        val controller = PairingHandshake.Controller(secret)
        val response = device.respond(id, controller.hello())
        if (response !is PairingHandshake.DeviceResponse.Challenge) return response
        val paired = assertIs<PairingHandshake.ControllerResult.Paired>(controller.finish(response.hello))
        return response.pending.complete(paired.finish)
    }

    @Test
    fun `a new pairing is active, listed, and its secret completes a handshake`() {
        val laptop = registry.pair("Laptop")
        assertEquals(PairingStatus.ACTIVE, registry.status(laptop.device.id))
        assertEquals(listOf(laptop.device), registry.list())
        assertIs<PairingHandshake.DeviceResult.Paired>(handshake(laptop.device.id, laptop.secret))
    }

    @Test
    fun `each controller has its own secret and cannot pair under another's id`() {
        val laptop = registry.pair("Laptop")
        val phone = registry.pair("Phone")
        assertFalse(laptop.secret.matches(phone.secret))
        assertIs<PairingHandshake.DeviceResult.Paired>(handshake(phone.device.id, phone.secret))
        // The controller rejects the device's proof, since it was made with the laptop's secret.
        val controller = PairingHandshake.Controller(phone.secret)
        val challenge = assertIs<PairingHandshake.DeviceResponse.Challenge>(device.respond(laptop.device.id, controller.hello()))
        assertIs<PairingHandshake.ControllerResult.Rejected>(controller.finish(challenge.hello))
    }

    @Test
    fun `an unknown controller is rejected before any challenge and counts as a failure`() {
        val gate = AuthGate(maxFailures = 1)
        val strictDevice = PairingHandshake.Device(registry::secretFor, gate)
        val response = strictDevice.respond("nobody", PairingHandshake.Controller(PairingSecret.generate()).hello())
        assertIs<PairingHandshake.DeviceResponse.Rejected>(response)
        assertTrue(gate.isLocked("nobody"))
    }

    @Test
    fun `revoking stops new handshakes and closes open channels`() {
        val laptop = registry.pair("Laptop")
        val keys = assertIs<PairingHandshake.DeviceResult.Paired>(handshake(laptop.device.id, laptop.secret)).keys
        val channel = registry.openChannel(laptop.device.id, keys)
        assertEquals(1, registry.openChannelCount(laptop.device.id))

        assertTrue(registry.revoke(laptop.device.id))
        assertTrue(channel.isClosed)
        assertFailsWith<SecureChannelException> { channel.seal("ls".toByteArray()) }
        assertEquals(PairingStatus.REVOKED, registry.status(laptop.device.id))
        assertNull(registry.secretFor(laptop.device.id))
        assertIs<PairingHandshake.DeviceResponse.Rejected>(handshake(laptop.device.id, laptop.secret))
        assertFailsWith<IllegalStateException> { registry.openChannel(laptop.device.id, keys) }
        assertFalse(registry.revoke(laptop.device.id), "revoking twice reports no change")
        assertEquals(PairingStatus.REVOKED, registry.list().single().status(clock.instant()))
    }

    @Test
    fun `revoking during a handshake keeps the controller from getting keys`() {
        val laptop = registry.pair("Laptop")
        val controller = PairingHandshake.Controller(laptop.secret)
        val challenge = assertIs<PairingHandshake.DeviceResponse.Challenge>(device.respond(laptop.device.id, controller.hello()))
        val finish = assertIs<PairingHandshake.ControllerResult.Paired>(controller.finish(challenge.hello)).finish
        registry.revoke(laptop.device.id)
        assertIs<PairingHandshake.DeviceResult.Rejected>(challenge.pending.complete(finish))
    }

    @Test
    fun `a pairing with a ttl expires and its channels can be swept closed`() {
        val guest = registry.pair("Guest tablet", ttl = Duration.ofHours(1))
        assertEquals(Instant.parse("2026-09-26T13:00:00Z"), guest.device.expiresAt)
        val keys = assertIs<PairingHandshake.DeviceResult.Paired>(handshake(guest.device.id, guest.secret)).keys
        val channel = registry.openChannel(guest.device.id, keys)

        assertEquals(emptyList(), registry.closeExpiredChannels())
        clock.advance(Duration.ofHours(1))
        assertEquals(PairingStatus.EXPIRED, registry.status(guest.device.id))
        assertNull(registry.secretFor(guest.device.id))
        assertIs<PairingHandshake.DeviceResponse.Rejected>(handshake(guest.device.id, guest.secret))
        assertEquals(listOf(guest.device.id), registry.closeExpiredChannels())
        assertTrue(channel.isClosed)
        assertEquals(0, registry.openChannelCount(guest.device.id))
    }

    @Test
    fun `revoking one controller leaves the others working`() {
        val laptop = registry.pair("Laptop")
        val phone = registry.pair("Phone")
        registry.revoke(laptop.device.id)
        assertNotNull(registry.secretFor(phone.device.id))
        assertIs<PairingHandshake.DeviceResult.Paired>(handshake(phone.device.id, phone.secret))
    }

    @Test
    fun `invalid inputs and secret printing`() {
        assertFailsWith<IllegalArgumentException> { registry.pair("  ") }
        assertFailsWith<IllegalArgumentException> { registry.pair("x", ttl = Duration.ZERO) }
        val pairing = registry.pair("Laptop")
        assertFalse(pairing.toString().contains(pairing.secret.encode()))
        assertNull(registry.status("missing"))
        assertFalse(registry.revoke("missing"))
    }
}
