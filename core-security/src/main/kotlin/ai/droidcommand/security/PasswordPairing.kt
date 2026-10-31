package ai.droidcommand.security

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password-authenticated fleet link. Complements (does not replace) cert
 * pins. Pending-refused inbound apps and outbound pin mismatches can be
 * linked after a password handshake; excluded apps can never be linked.
 *
 * Protocol v1 is HMAC-SHA256 challenge-response over Messenger /
 * COMPANION_ASSIST. The password is stored as PBKDF2-HMAC-SHA256
 * (never plaintext) and must sit in encrypted storage on Android.
 * Attempts are rate-limited. Linking is OFF by default, needs an
 * on-screen confirmation, and is revocable per package.
 *
 * See `docs/PASSWORD_PAIRING.md`.
 */
object PasswordPairing {
    const val PROTOCOL = "password-pairing"
    const val PROTOCOL_VERSION = 1
    const val HMAC_INFO = "dca-password-pairing-v1"
    const val PBKDF2_ITERATIONS = 210_000
    const val KEY_BYTES = 32
    const val SALT_BYTES = 16
    const val NONCE_BYTES = 32
    const val CHALLENGE_TTL_MS = 5 * 60_000L
    const val MAX_FAILURES = 5
    const val LOCKOUT_MS = 15 * 60_000L
    const val MIN_PASSWORD_CHARS = 8
    const val VAULT_SECRET_ID = "password-pairing.snapshot"

    val EXCLUDED_PACKAGES: Set<String> = InboundEntryPointCatalog.EXCLUDED
        .flatMap { it.packages }
        .toSet()

    /** Same-signing companions: a password link does not bypass their OS signature permission. */
    val SAME_SIGNING_PACKAGES: Set<String> = setOf(
        "ai.hackerai.companion",
        "ai.pentestswarm.companion",
        EdlCompanionIds.PACKAGE,
    )

    fun normalizeCert(raw: String): String? {
        val hex = raw.trim().replace(":", "").lowercase()
        return hex.takeIf { it.matches(Regex("^[0-9a-f]{64}$")) }
    }

    fun isExcluded(packageName: String): Boolean = packageName in EXCLUDED_PACKAGES

    fun hmacMessage(packageName: String, certCanonical: String, nonceHex: String): ByteArray =
        "$HMAC_INFO|$packageName|$certCanonical|$nonceHex".toByteArray(Charsets.UTF_8)
}

data class PasswordRecord(
    val saltHex: String,
    val keyHex: String,
    val iterations: Int = PasswordPairing.PBKDF2_ITERATIONS,
)

data class PasswordPairingLink(
    val packageName: String,
    val observedCertSha256: String,
    val linkedAtMs: Long,
) {
    init {
        require(packageName.isNotBlank()) { "link package is blank" }
        require(PasswordPairing.normalizeCert(observedCertSha256) != null) {
            "link cert must be 64 hex chars"
        }
        require(!PasswordPairing.isExcluded(packageName)) {
            "excluded apps can never be linked"
        }
    }
}

data class PasswordPairingLockout(
    val packageName: String,
    val failures: Int = 0,
    val lockedUntilMs: Long = 0L,
)

data class PairingChallenge(
    val id: String,
    val packageName: String,
    val nonceHex: String,
    val expiresAtMs: Long,
)

data class PairingProof(
    val challengeId: String? = null,
    val nonceHex: String? = null,
    val macHex: String? = null,
) {
    fun isBlank(): Boolean = challengeId.isNullOrBlank() && nonceHex.isNullOrBlank() && macHex.isNullOrBlank()
}

data class PasswordPairingSnapshot(
    val enabled: Boolean = false,
    val password: PasswordRecord? = null,
    val links: List<PasswordPairingLink> = emptyList(),
    val lockouts: List<PasswordPairingLockout> = emptyList(),
)

sealed class PasswordPairingDecision {
    data class Allowed(
        val packageName: String,
        val certSha256: String,
        val newlyLinked: Boolean,
        val message: String,
    ) : PasswordPairingDecision()

    data class Refused(
        val reason: String,
        val locked: Boolean = false,
        val confirmationRequired: Boolean = false,
        val needsPassword: Boolean = false,
    ) : PasswordPairingDecision()
}

interface PasswordPairingStore {
    fun load(): PasswordPairingSnapshot

    fun save(snapshot: PasswordPairingSnapshot)
}

class InMemoryPasswordPairingStore(
    initial: PasswordPairingSnapshot = PasswordPairingSnapshot(),
) : PasswordPairingStore {
    private var snapshot = initial

    @Synchronized override fun load(): PasswordPairingSnapshot = snapshot

    @Synchronized override fun save(snapshot: PasswordPairingSnapshot) {
        this.snapshot = snapshot
    }
}

/**
 * JVM-testable password pairing. [random] is injectable so tests are
 * deterministic. Never logs the password, salt, key, or HMAC.
 */
class PasswordPairingService(
    private val store: PasswordPairingStore,
    private val random: () -> SecureRandom = { SecureRandom() },
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val challenges = linkedMapOf<String, PairingChallenge>()

    fun snapshot(): PasswordPairingSnapshot = store.load()

    fun isEnabled(): Boolean = snapshot().enabled

    fun hasPassword(): Boolean = snapshot().password != null

    fun linkedPackages(): List<PasswordPairingLink> = snapshot().links

    fun isLinked(packageName: String, observedCert: String): Boolean {
        val cert = PasswordPairing.normalizeCert(observedCert) ?: return false
        return snapshot().links.any { it.packageName == packageName && it.observedCertSha256 == cert }
    }

    fun enable(on: Boolean) {
        save(snapshot().copy(enabled = on))
    }

    fun setPassword(password: String): PasswordPairingDecision {
        if (password.length < PasswordPairing.MIN_PASSWORD_CHARS) {
            return PasswordPairingDecision.Refused(
                "Password must be at least ${PasswordPairing.MIN_PASSWORD_CHARS} characters.",
            )
        }
        val salt = ByteArray(PasswordPairing.SALT_BYTES)
        random().nextBytes(salt)
        val key = deriveKey(password, salt, PasswordPairing.PBKDF2_ITERATIONS)
        save(
            snapshot().copy(
                password = PasswordRecord(
                    saltHex = toHex(salt),
                    keyHex = toHex(key),
                    iterations = PasswordPairing.PBKDF2_ITERATIONS,
                ),
            ),
        )
        key.fill(0)
        return PasswordPairingDecision.Allowed(
            packageName = "",
            certSha256 = "",
            newlyLinked = false,
            message = "Password saved (hashed).",
        )
    }

    fun clearPassword() {
        save(snapshot().copy(password = null, links = emptyList(), enabled = false))
        challenges.clear()
    }

    fun revoke(packageName: String): Boolean {
        val current = snapshot()
        val next = current.links.filterNot { it.packageName == packageName }
        if (next.size == current.links.size) return false
        save(current.copy(links = next))
        return true
    }

    fun issueChallenge(packageName: String): PasswordPairingDecision {
        if (PasswordPairing.isExcluded(packageName)) {
            return PasswordPairingDecision.Refused("Excluded apps can never be linked.")
        }
        if (!isEnabled()) {
            return PasswordPairingDecision.Refused("Password pairing is OFF.")
        }
        if (!hasPassword()) {
            return PasswordPairingDecision.Refused("No pairing password is set.")
        }
        lockoutDecision(packageName)?.let { return it }
        val nonce = ByteArray(PasswordPairing.NONCE_BYTES)
        random().nextBytes(nonce)
        val challenge = PairingChallenge(
            id = toHex(nonce.copyOf(8)),
            packageName = packageName,
            nonceHex = toHex(nonce),
            expiresAtMs = clock() + PasswordPairing.CHALLENGE_TTL_MS,
        )
        challenges[challenge.id] = challenge
        return PasswordPairingDecision.Allowed(
            packageName = packageName,
            certSha256 = "",
            newlyLinked = false,
            message = challenge.id + ":" + challenge.nonceHex,
        )
    }

    /**
     * Complete a peer HMAC handshake. [screenConfirmed] must be true —
     * the owner taps Allow on this device.
     */
    fun completeHandshake(
        packageName: String,
        observedCert: String,
        proof: PairingProof,
        screenConfirmed: Boolean,
    ): PasswordPairingDecision {
        if (!screenConfirmed) {
            return PasswordPairingDecision.Refused(
                "On-screen confirmation is required before a password link.",
                confirmationRequired = true,
            )
        }
        val gated = gate(packageName, observedCert) ?: return excludedOrDisabled(packageName)
        val cert = gated
        tofuConflict(packageName, cert)?.let { return it }
        lockoutDecision(packageName)?.let { return it }
        val challenge = resolveChallenge(packageName, proof)
            ?: return failAttempt(packageName, "Pairing challenge is missing or expired.")
        val password = snapshot().password
            ?: return PasswordPairingDecision.Refused("No pairing password is set.")
        val expected = hmacHex(password, packageName, cert, challenge.nonceHex)
        val presented = proof.macHex?.trim()?.lowercase()
        challenges.remove(challenge.id)
        if (presented.isNullOrBlank() || !constantTimeEquals(expected, presented)) {
            return failAttempt(packageName, "Pairing HMAC did not match.")
        }
        return link(packageName, cert)
    }

    /**
     * Hub-local confirmation: the owner types the password on this device
     * after seeing the package + cert. Same TOFU bind as a successful HMAC.
     */
    fun confirmWithPassword(
        packageName: String,
        observedCert: String,
        password: String,
        screenConfirmed: Boolean,
    ): PasswordPairingDecision {
        if (!screenConfirmed) {
            return PasswordPairingDecision.Refused(
                "On-screen confirmation is required before a password link.",
                confirmationRequired = true,
            )
        }
        val gated = gate(packageName, observedCert) ?: return excludedOrDisabled(packageName)
        val cert = gated
        tofuConflict(packageName, cert)?.let { return it }
        lockoutDecision(packageName)?.let { return it }
        val record = snapshot().password
            ?: return PasswordPairingDecision.Refused("No pairing password is set.")
        val derived = deriveKey(password, fromHex(record.saltHex), record.iterations)
        val ok = constantTimeEquals(toHex(derived), record.keyHex)
        derived.fill(0)
        if (!ok) {
            return failAttempt(packageName, "Pairing password did not match.")
        }
        return link(packageName, cert)
    }

    /**
     * Later reconnect: already-linked package + unchanged TOFU cert.
     * A changed cert is refused (re-sign is not auto-trusted).
     */
    fun acceptLinked(packageName: String, observedCert: String): PasswordPairingDecision {
        if (PasswordPairing.isExcluded(packageName)) {
            return PasswordPairingDecision.Refused("Excluded apps can never be linked.")
        }
        if (!isEnabled()) {
            return PasswordPairingDecision.Refused("Password pairing is OFF.")
        }
        val cert = PasswordPairing.normalizeCert(observedCert)
            ?: return PasswordPairingDecision.Refused("Observed signing cert is missing or malformed.")
        val link = snapshot().links.find { it.packageName == packageName }
            ?: return PasswordPairingDecision.Refused("No password link for $packageName.")
        if (link.observedCertSha256 != cert) {
            return PasswordPairingDecision.Refused(
                "Password link for $packageName refused: signing cert changed since TOFU bind.",
            )
        }
        return PasswordPairingDecision.Allowed(
            packageName = packageName,
            certSha256 = cert,
            newlyLinked = false,
            message = "Password-linked TOFU cert still matches.",
        )
    }

    fun evaluateInbound(
        packageName: String,
        observedCerts: Collection<String>,
        proof: PairingProof?,
        typedPassword: String?,
        screenConfirmed: Boolean,
    ): PasswordPairingDecision {
        val cert = observedCerts.mapNotNull { PasswordPairing.normalizeCert(it) }.firstOrNull()
            ?: return PasswordPairingDecision.Refused("Observed signing cert is missing or malformed.")
        acceptLinked(packageName, cert).let { linked ->
            if (linked is PasswordPairingDecision.Allowed) return linked
        }
        if (proof != null && !proof.isBlank()) {
            return completeHandshake(packageName, cert, proof, screenConfirmed)
        }
        if (!typedPassword.isNullOrEmpty()) {
            return confirmWithPassword(packageName, cert, typedPassword, screenConfirmed)
        }
        if (!isEnabled() || !hasPassword()) {
            return PasswordPairingDecision.Refused("Password pairing is OFF or no password is set.")
        }
        return PasswordPairingDecision.Refused(
            "Password pairing can link $packageName after on-screen confirmation.",
            confirmationRequired = true,
            needsPassword = true,
        )
    }

    private fun gate(packageName: String, observedCert: String): String? {
        if (PasswordPairing.isExcluded(packageName)) return null
        if (!isEnabled() || !hasPassword()) return null
        return PasswordPairing.normalizeCert(observedCert)
    }

    private fun tofuConflict(packageName: String, cert: String): PasswordPairingDecision? {
        val existing = snapshot().links.find { it.packageName == packageName } ?: return null
        if (existing.observedCertSha256 == cert) return null
        return PasswordPairingDecision.Refused(
            "Password link for $packageName refused: signing cert changed since TOFU bind.",
        )
    }

    private fun excludedOrDisabled(packageName: String): PasswordPairingDecision =
        if (PasswordPairing.isExcluded(packageName)) {
            PasswordPairingDecision.Refused("Excluded apps can never be linked.")
        } else if (!isEnabled()) {
            PasswordPairingDecision.Refused("Password pairing is OFF.")
        } else {
            PasswordPairingDecision.Refused("No pairing password is set.")
        }

    private fun link(packageName: String, cert: String): PasswordPairingDecision {
        val current = snapshot()
        val links = current.links.filterNot { it.packageName == packageName } +
            PasswordPairingLink(packageName, cert, clock())
        val lockouts = current.lockouts.filterNot { it.packageName == packageName }
        save(current.copy(links = links, lockouts = lockouts))
        return PasswordPairingDecision.Allowed(
            packageName = packageName,
            certSha256 = cert,
            newlyLinked = true,
            message = "Linked $packageName by password (TOFU cert bound).",
        )
    }

    private fun resolveChallenge(packageName: String, proof: PairingProof): PairingChallenge? {
        val now = clock()
        challenges.entries.removeIf { it.value.expiresAtMs < now }
        val byId = proof.challengeId?.let { challenges[it] }
        val byNonce = proof.nonceHex?.lowercase()?.let { nonce ->
            challenges.values.firstOrNull { it.nonceHex == nonce }
        }
        val challenge = byId ?: byNonce ?: return null
        if (challenge.packageName != packageName) return null
        if (challenge.expiresAtMs < now) return null
        return challenge
    }

    private fun lockoutDecision(packageName: String): PasswordPairingDecision? {
        val row = snapshot().lockouts.find { it.packageName == packageName } ?: return null
        if (row.lockedUntilMs > clock()) {
            return PasswordPairingDecision.Refused(
                "Password pairing locked for $packageName after repeated failures.",
                locked = true,
            )
        }
        return null
    }

    private fun failAttempt(packageName: String, reason: String): PasswordPairingDecision {
        val current = snapshot()
        val existing = current.lockouts.find { it.packageName == packageName }
            ?: PasswordPairingLockout(packageName)
        val failures = existing.failures + 1
        val lockedUntil = if (failures >= PasswordPairing.MAX_FAILURES) clock() + PasswordPairing.LOCKOUT_MS else 0L
        val next = PasswordPairingLockout(packageName, failures, lockedUntil)
        save(current.copy(lockouts = current.lockouts.filterNot { it.packageName == packageName } + next))
        return PasswordPairingDecision.Refused(reason, locked = lockedUntil > 0L)
    }

    private fun hmacHex(record: PasswordRecord, packageName: String, cert: String, nonceHex: String): String {
        val key = fromHex(record.keyHex)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        val out = toHex(mac.doFinal(PasswordPairing.hmacMessage(packageName, cert, nonceHex)))
        key.fill(0)
        return out
    }

    private fun deriveKey(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, PasswordPairing.KEY_BYTES * 8)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun save(snapshot: PasswordPairingSnapshot) {
        store.save(snapshot)
    }

    private fun toHex(bytes: ByteArray): String = bytes.joinToString("") { b -> "%02x".format(b) }

    private fun fromHex(hex: String): ByteArray {
        val clean = hex.replace(":", "")
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        val left = a.toByteArray(Charsets.UTF_8)
        val right = b.toByteArray(Charsets.UTF_8)
        val ok = MessageDigest.isEqual(left, right)
        left.fill(0)
        right.fill(0)
        return ok
    }

    /** Test helper: compute the peer HMAC for a known password + issued challenge. */
    fun debugMac(packageName: String, cert: String, nonceHex: String): String {
        val password = snapshot().password ?: error("no password")
        val normalized = PasswordPairing.normalizeCert(cert) ?: error("bad cert")
        return hmacHex(password, packageName, normalized, nonceHex)
    }
}

/**
 * Snapshot codec for encrypted vault storage. Never logs the password
 * hash, salt, or HMAC. Excluded-package rows are dropped on decode.
 */
object PasswordPairingCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(snapshot: PasswordPairingSnapshot): String = json.encodeToString(SnapshotDto.from(snapshot))

    fun decode(raw: String): PasswordPairingSnapshot? = runCatching {
        json.decodeFromString(SnapshotDto.serializer(), raw).toModel()
    }.getOrNull()

    @Serializable
    private data class SnapshotDto(
        val enabled: Boolean = false,
        val password: PasswordDto? = null,
        val links: List<LinkDto> = emptyList(),
        val lockouts: List<LockoutDto> = emptyList(),
    ) {
        fun toModel(): PasswordPairingSnapshot = PasswordPairingSnapshot(
            enabled = enabled,
            password = password?.let { PasswordRecord(it.saltHex, it.keyHex, it.iterations) },
            links = links.mapNotNull { row ->
                runCatching {
                    PasswordPairingLink(row.packageName, row.observedCertSha256, row.linkedAtMs)
                }.getOrNull()
            },
            lockouts = lockouts.map { PasswordPairingLockout(it.packageName, it.failures, it.lockedUntilMs) },
        )

        companion object {
            fun from(snapshot: PasswordPairingSnapshot) = SnapshotDto(
                enabled = snapshot.enabled,
                password = snapshot.password?.let {
                    PasswordDto(it.saltHex, it.keyHex, it.iterations)
                },
                links = snapshot.links.map {
                    LinkDto(it.packageName, it.observedCertSha256, it.linkedAtMs)
                },
                lockouts = snapshot.lockouts.map {
                    LockoutDto(it.packageName, it.failures, it.lockedUntilMs)
                },
            )
        }
    }

    @Serializable
    private data class PasswordDto(
        val saltHex: String,
        val keyHex: String,
        val iterations: Int = PasswordPairing.PBKDF2_ITERATIONS,
    )

    @Serializable
    private data class LinkDto(
        val packageName: String,
        val observedCertSha256: String,
        val linkedAtMs: Long,
    )

    @Serializable
    private data class LockoutDto(
        val packageName: String,
        val failures: Int = 0,
        val lockedUntilMs: Long = 0L,
    )
}
