package ai.droidcommand.remote.pairing

import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PairingHandshakeTest {
    private val secret = PairingSecret.generate()

    /** Runs all three messages through their wire encoding, as a real transport would. */
    private fun pair(
        controllerSecret: PairingSecret,
        device: PairingHandshake.Device,
        peerId: String = "controller-1",
    ): Pair<PairingHandshake.ControllerResult, PairingHandshake.DeviceResult?> {
        val controller = PairingHandshake.Controller(controllerSecret)
        val hello = PairingHandshake.ClientHello.fromBytes(controller.hello().toBytes())
        val response = device.respond(peerId, hello)
        if (response is PairingHandshake.DeviceResponse.LockedOut) {
            return PairingHandshake.ControllerResult.Rejected("locked out") to PairingHandshake.DeviceResult.LockedOut(response.until)
        }
        val challenge = assertIs<PairingHandshake.DeviceResponse.Challenge>(response)
        val controllerResult = controller.finish(PairingHandshake.ServerHello.fromBytes(challenge.hello.toBytes()))
        if (controllerResult !is PairingHandshake.ControllerResult.Paired) return controllerResult to null
        return controllerResult to challenge.pending.complete(PairingHandshake.ClientFinish.fromBytes(controllerResult.finish.toBytes()))
    }

    @Test
    fun `both sides pair and can exchange records in both directions`() {
        val (controllerResult, deviceResult) = pair(secret, PairingHandshake.Device(secret))
        val controllerChannel = SecureChannel(assertIs<PairingHandshake.ControllerResult.Paired>(controllerResult).keys)
        val deviceChannel = SecureChannel(assertIs<PairingHandshake.DeviceResult.Paired>(deviceResult).keys)

        assertEquals("pm list packages", deviceChannel.open(controllerChannel.seal("pm list packages".toByteArray())).decodeToString())
        assertEquals("package:ai.droidcommand", controllerChannel.open(deviceChannel.seal("package:ai.droidcommand".toByteArray())).decodeToString())
    }

    @Test
    fun `the controller rejects a device that does not know the secret`() {
        val (controllerResult, deviceResult) = pair(secret, PairingHandshake.Device(PairingSecret.generate()))
        assertIs<PairingHandshake.ControllerResult.Rejected>(controllerResult)
        assertEquals(null, deviceResult)
    }

    @Test
    fun `the device rejects a controller proof made with the wrong secret and records a failure`() {
        val gate = AuthGate()
        val device = PairingHandshake.Device(secret, gate)
        val challenge = assertIs<PairingHandshake.DeviceResponse.Challenge>(
            device.respond("mallory", PairingHandshake.Controller(secret).hello()),
        )
        // A controller that doesn't hold the secret can't make a valid proof; forge one.
        val forged = PairingHandshake.ClientFinish(ByteArray(PairingHandshake.PROOF_BYTES))
        assertIs<PairingHandshake.DeviceResult.Rejected>(challenge.pending.complete(forged))
        assertFalse(gate.isLocked("mallory"))
    }

    @Test
    fun `the device refuses to reflect its own proof back as the controller's`() {
        val controller = PairingHandshake.Controller(secret)
        val device = PairingHandshake.Device(secret)
        val challenge = assertIs<PairingHandshake.DeviceResponse.Challenge>(device.respond("peer", controller.hello()))
        val reflected = PairingHandshake.ClientFinish(challenge.hello.serverProof)
        assertIs<PairingHandshake.DeviceResult.Rejected>(challenge.pending.complete(reflected))
    }

    @Test
    fun `a proof recorded from one handshake does not complete another`() {
        val device = PairingHandshake.Device(secret)
        val first = PairingHandshake.Controller(secret)
        val recordedHello = first.hello()
        val firstChallenge = assertIs<PairingHandshake.DeviceResponse.Challenge>(device.respond("peer", recordedHello))
        val firstFinish = assertIs<PairingHandshake.ControllerResult.Paired>(first.finish(firstChallenge.hello)).finish
        assertIs<PairingHandshake.DeviceResult.Paired>(firstChallenge.pending.complete(firstFinish))

        // An eavesdropper replays the recorded hello and finish; the device's fresh nonce changes the expected proof.
        val replayChallenge = assertIs<PairingHandshake.DeviceResponse.Challenge>(
            device.respond("peer", recordedHello),
        )
        assertIs<PairingHandshake.DeviceResult.Rejected>(replayChallenge.pending.complete(firstFinish))
    }

    @Test
    fun `five bad proofs lock the peer out before any further handshake`() {
        val gate = AuthGate()
        val device = PairingHandshake.Device(secret, gate)
        val wrong = PairingSecret.generate()
        repeat(5) {
            val controller = PairingHandshake.Controller(secret)
            val challenge = assertIs<PairingHandshake.DeviceResponse.Challenge>(device.respond("mallory", controller.hello()))
            // Each attempt proves with the wrong secret.
            val forgedProof = Hkdf.hmac(wrong.toByteArray(), challenge.hello.serverNonce)
            challenge.pending.complete(PairingHandshake.ClientFinish(forgedProof))
        }
        assertTrue(gate.isLocked("mallory"))
        val (_, deviceResult) = pair(secret, device, peerId = "mallory")
        assertIs<PairingHandshake.DeviceResult.LockedOut>(deviceResult)
        // Other peers are unaffected.
        assertIs<PairingHandshake.DeviceResult.Paired>(pair(secret, device, peerId = "owner").second)
    }

    @Test
    fun `each handshake derives different, direction-specific keys`() {
        val (a, _) = pair(secret, PairingHandshake.Device(secret))
        val (b, _) = pair(secret, PairingHandshake.Device(secret))
        val keysA = assertIs<PairingHandshake.ControllerResult.Paired>(a).keys
        val keysB = assertIs<PairingHandshake.ControllerResult.Paired>(b).keys
        assertNotEquals(keysA.sendKey.toList(), keysA.receiveKey.toList())
        assertNotEquals(keysA.sendKey.toList(), keysB.sendKey.toList())
    }

    @Test
    fun `the same nonces and secret always derive the same keys`() {
        val fixedSecret = PairingSecret(ByteArray(32) { it.toByte() })
        fun fixedRandom(fill: Byte) = object : SecureRandom() {
            override fun nextBytes(bytes: ByteArray) = bytes.fill(fill)
        }
        fun run(): PairingHandshake.ControllerResult.Paired {
            val controller = PairingHandshake.Controller(fixedSecret, fixedRandom(1))
            val device = PairingHandshake.Device(fixedSecret, random = fixedRandom(2))
            val challenge = assertIs<PairingHandshake.DeviceResponse.Challenge>(device.respond("p", controller.hello()))
            return assertIs(controller.finish(challenge.hello))
        }
        val first = run()
        val second = run()
        assertContentEquals(first.keys.sendKey, second.keys.sendKey)
        assertContentEquals(first.finish.clientProof, second.finish.clientProof)
    }

    @Test
    fun `wire frames reject the wrong length or version`() {
        assertFailsWith<IllegalArgumentException> { PairingHandshake.ClientHello.fromBytes(ByteArray(10)) }
        val badVersion = ByteArray(1 + PairingHandshake.NONCE_BYTES).also { it[0] = 9 }
        assertFailsWith<IllegalArgumentException> { PairingHandshake.ClientHello.fromBytes(badVersion) }
    }

    @Test
    fun `a controller handshake is single use`() {
        val controller = PairingHandshake.Controller(secret)
        controller.hello()
        assertFailsWith<IllegalStateException> { controller.hello() }
    }

    @Test
    fun `pairing secrets round-trip through their encoded form and never print`() {
        val decoded = PairingSecret.decode(secret.encode())
        assertEquals(secret, decoded)
        assertFalse(secret.toString().contains(secret.encode()))
        assertFailsWith<IllegalArgumentException> { PairingSecret(ByteArray(16)) }
    }
}
