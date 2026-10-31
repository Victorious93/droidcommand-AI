package ai.droidcommand.security

import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PasswordPairingServiceTest {
    private val sms = "com.victorious.sms"
    private val zombie = "com.victorious.zombie"
    private val certA = "aa".repeat(32)
    private val certB = "bb".repeat(32)
    private val password = "correct-horse"

    private fun service(clock: () -> Long = { 1_000L }): PasswordPairingService {
        val random = object : SecureRandom() {
            private var n = 1
            override fun nextBytes(bytes: ByteArray) {
                for (i in bytes.indices) bytes[i] = (n++ and 0xff).toByte()
            }
        }
        return PasswordPairingService(InMemoryPasswordPairingStore(), { random }, clock)
    }

    private fun ready(): PasswordPairingService {
        val svc = service()
        svc.setPassword(password)
        svc.enable(true)
        return svc
    }

    @Test
    fun `pairing is off by default and excluded apps are never linked`() {
        val svc = service()
        assertFalse(svc.isEnabled())
        assertFalse(svc.hasPassword())
        assertIs<PasswordPairingDecision.Refused>(svc.issueChallenge(sms))
        assertTrue(
            assertIs<PasswordPairingDecision.Refused>(
                svc.confirmWithPassword(zombie, certA, password, screenConfirmed = true),
            ).reason.contains("Excluded"),
        )
        assertTrue(
            assertIs<PasswordPairingDecision.Refused>(
                svc.completeHandshake(zombie, certA, PairingProof(macHex = "00"), screenConfirmed = true),
            ).reason.contains("Excluded"),
        )
    }

    @Test
    fun `short password is refused and the hash is never stored as plaintext`() {
        val svc = service()
        val refused = assertIs<PasswordPairingDecision.Refused>(svc.setPassword("short"))
        assertTrue(refused.reason.contains("8"))
        assertFalse(svc.hasPassword())
        assertIs<PasswordPairingDecision.Allowed>(svc.setPassword(password))
        val record = svc.snapshot().password
        assertNotNull(record)
        assertFalse(record.keyHex.contains(password))
        assertEquals(PasswordPairing.PBKDF2_ITERATIONS, record.iterations)
        assertEquals(PasswordPairing.SALT_BYTES * 2, record.saltHex.length)
    }

    @Test
    fun `hub-local confirm binds TOFU and later reconnects accept the same cert only`() {
        val svc = ready()
        val first = assertIs<PasswordPairingDecision.Allowed>(
            svc.confirmWithPassword(sms, certA, password, screenConfirmed = true),
        )
        assertTrue(first.newlyLinked)
        assertTrue(svc.isLinked(sms, certA))
        assertIs<PasswordPairingDecision.Allowed>(svc.acceptLinked(sms, certA))
        val changed = assertIs<PasswordPairingDecision.Refused>(svc.acceptLinked(sms, certB))
        assertTrue(changed.reason.contains("changed"))
        val replay = assertIs<PasswordPairingDecision.Refused>(
            svc.confirmWithPassword(sms, certB, password, screenConfirmed = true),
        )
        assertTrue(replay.reason.contains("changed"))
        assertTrue(svc.isLinked(sms, certA))
        assertFalse(svc.isLinked(sms, certB))
    }

    @Test
    fun `on-screen confirmation is required`() {
        val svc = ready()
        val refused = assertIs<PasswordPairingDecision.Refused>(
            svc.confirmWithPassword(sms, certA, password, screenConfirmed = false),
        )
        assertTrue(refused.confirmationRequired)
        assertFalse(svc.isLinked(sms, certA))
    }

    @Test
    fun `HMAC handshake matches the issued nonce and refuses a bad mac`() {
        val svc = ready()
        val issued = assertIs<PasswordPairingDecision.Allowed>(svc.issueChallenge(sms))
        val challengeId = issued.message.substringBefore(":")
        val nonce = issued.message.substringAfter(":")
        val mac = svc.debugMac(sms, certA, nonce)
        val ok = assertIs<PasswordPairingDecision.Allowed>(
            svc.completeHandshake(
                sms,
                certA,
                PairingProof(challengeId = challengeId, nonceHex = nonce, macHex = mac),
                screenConfirmed = true,
            ),
        )
        assertTrue(ok.newlyLinked)

        val other = ready()
        val second = assertIs<PasswordPairingDecision.Allowed>(other.issueChallenge(sms))
        val bad = assertIs<PasswordPairingDecision.Refused>(
            other.completeHandshake(
                sms,
                certA,
                PairingProof(
                    challengeId = second.message.substringBefore(":"),
                    nonceHex = second.message.substringAfter(":"),
                    macHex = "00".repeat(32),
                ),
                screenConfirmed = true,
            ),
        )
        assertTrue(bad.reason.contains("HMAC"))
    }

    @Test
    fun `five failures lock the package and excluded catalog packages stay unlinked`() {
        val svc = ready()
        repeat(PasswordPairing.MAX_FAILURES - 1) {
            assertFalse(
                assertIs<PasswordPairingDecision.Refused>(
                    svc.confirmWithPassword(sms, certA, "wrong-password", screenConfirmed = true),
                ).locked,
            )
        }
        val locked = assertIs<PasswordPairingDecision.Refused>(
            svc.confirmWithPassword(sms, certA, "wrong-password", screenConfirmed = true),
        )
        assertTrue(locked.locked)
        val still = assertIs<PasswordPairingDecision.Refused>(
            svc.confirmWithPassword(sms, certA, password, screenConfirmed = true),
        )
        assertTrue(still.locked)
        assertFalse(svc.isLinked(sms, certA))
        for (pkg in PasswordPairing.EXCLUDED_PACKAGES) {
            assertTrue(PasswordPairing.isExcluded(pkg), pkg)
        }
        assertTrue("com.victorious.sms" !in PasswordPairing.EXCLUDED_PACKAGES)
        assertTrue("ai.hackerai.companion" in PasswordPairing.SAME_SIGNING_PACKAGES)
        assertTrue("ai.pentestswarm.companion" in PasswordPairing.SAME_SIGNING_PACKAGES)
        assertTrue(EdlCompanionIds.PACKAGE in PasswordPairing.SAME_SIGNING_PACKAGES)
    }

    @Test
    fun `revoke and clearPassword drop links`() {
        val svc = ready()
        svc.confirmWithPassword(sms, certA, password, screenConfirmed = true)
        assertTrue(svc.revoke(sms))
        assertFalse(svc.isLinked(sms, certA))
        svc.confirmWithPassword(sms, certA, password, screenConfirmed = true)
        svc.clearPassword()
        assertFalse(svc.isEnabled())
        assertFalse(svc.hasPassword())
        assertTrue(svc.linkedPackages().isEmpty())
    }

    @Test
    fun `codec round-trips a snapshot and drops excluded rows`() {
        val original = PasswordPairingSnapshot(
            enabled = true,
            password = PasswordRecord("ab".repeat(8), "cd".repeat(32), 210_000),
            links = listOf(PasswordPairingLink(sms, certA, 9L)),
            lockouts = listOf(PasswordPairingLockout(sms, 2, 0L)),
        )
        val decoded = PasswordPairingCodec.decode(PasswordPairingCodec.encode(original))
        assertEquals(original, decoded)
        val tampered = """{"enabled":true,"links":[{"packageName":"$zombie","observedCertSha256":"$certA","linkedAtMs":1}]}"""
        val cleaned = PasswordPairingCodec.decode(tampered)
        assertNotNull(cleaned)
        assertTrue(cleaned.links.isEmpty())
        assertNull(PasswordPairingCodec.decode("{"))
    }

    @Test
    fun `evaluateInbound offers confirmation then accepts a typed password`() {
        val svc = ready()
        val offer = assertIs<PasswordPairingDecision.Refused>(
            svc.evaluateInbound(sms, listOf(certA), proof = null, typedPassword = null, screenConfirmed = false),
        )
        assertTrue(offer.confirmationRequired)
        assertTrue(offer.needsPassword)
        val linked = assertIs<PasswordPairingDecision.Allowed>(
            svc.evaluateInbound(sms, listOf(certA), proof = null, typedPassword = password, screenConfirmed = true),
        )
        assertTrue(linked.newlyLinked)
        assertIs<PasswordPairingDecision.Allowed>(
            svc.evaluateInbound(sms, listOf(certA), proof = null, typedPassword = null, screenConfirmed = false),
        )
    }
}
