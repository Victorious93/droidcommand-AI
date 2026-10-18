package ai.droidcommand.security

import java.security.MessageDigest

/**
 * SHA-256 of an Android app signing certificate, stored as uppercase
 * colon-separated hex (`AA:BB:…`). Comparison ignores case and separators.
 *
 * This is the controller-side pin for a fleet NODE's signing identity.
 * It is not a shared fleet keystore and is not [DependencyGuard].
 */
class SigningCertSha256 private constructor(val canonical: String) {
    fun matches(other: String): Boolean = parse(other)?.canonical == canonical

    override fun equals(other: Any?): Boolean =
        other is SigningCertSha256 && other.canonical == canonical

    override fun hashCode(): Int = canonical.hashCode()

    override fun toString(): String = canonical

    companion object {
        fun parse(raw: String): SigningCertSha256? {
            val hex = raw.filter { it.isHexDigit() }
            if (hex.length != 64) return null
            val canonical = hex.uppercase().chunked(2).joinToString(":")
            return SigningCertSha256(canonical)
        }

        fun parseOrThrow(raw: String): SigningCertSha256 =
            parse(raw) ?: throw IllegalArgumentException("Not a SHA-256 certificate fingerprint: $raw")

        fun fromCertificateBytes(bytes: ByteArray): SigningCertSha256 {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            val hex = digest.joinToString("") { byte -> "%02X".format(byte) }
            return parseOrThrow(hex)
        }

        private fun Char.isHexDigit(): Boolean =
            this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
    }
}

/**
 * What to do when a package has **no** catalogued expected cert.
 *
 * Fail-closed ([REFUSE]) is the stronger posture, but the documented
 * **default is [ALLOW]** so existing flows for members whose cert is
 * still `[Unknown]` (File Manager, Keyboard `.bench`, Mesh release,
 * KYC, victorious-flash, …) keep working. Flip to [REFUSE] via
 * [fromEnvironment] (`DROIDCOMMAND_NODE_CERT_MISSING_EXPECTED=refuse`)
 * only once the catalogue is complete — it is still partial.
 *
 * When expected certs **are** catalogued, mismatch and missing-observed
 * always refuse. That path is not configurable.
 */
enum class MissingExpectedCertPolicy {
    ALLOW,
    REFUSE,
    ;

    companion object {
        const val ENV_NAME = "DROIDCOMMAND_NODE_CERT_MISSING_EXPECTED"

        fun fromEnvironment(getenv: (String) -> String? = System::getenv): MissingExpectedCertPolicy =
            when (getenv(ENV_NAME)?.trim()?.lowercase()) {
                "refuse", "fail-closed", "deny" -> REFUSE
                else -> ALLOW
            }
    }
}

/**
 * One Android applicationId the controller may bind/command, plus the
 * signing-cert SHA-256 values it is willing to accept for that package.
 *
 * [expectedCerts] empty means the fingerprint is `[Unknown]` — do not
 * invent one. Debug and release (or any other accepted identities) are
 * listed together; a match against any entry is enough.
 */
data class NodeSigningIdentity(
    val packageName: String,
    val expectedCerts: List<SigningCertSha256>,
    val memberId: String?,
    val integrationClass: FleetIntegrationClass?,
    val notes: String,
) {
    init {
        require(packageName.isNotBlank()) { "packageName must not be blank" }
    }
}

sealed class NodeSignatureDecision {
    abstract val packageName: String
    abstract val message: String

    data class Allowed(
        override val packageName: String,
        val matchedFingerprint: String?,
        override val message: String,
    ) : NodeSignatureDecision()

    data class Refused(
        override val packageName: String,
        val kind: NodeSignatureRefusal,
        override val message: String,
        val observed: List<String> = emptyList(),
        val expected: List<String> = emptyList(),
    ) : NodeSignatureDecision()
}

enum class NodeSignatureRefusal {
    /** Observed cert is present but none of it matches the catalogued set. */
    CERT_MISMATCH,

    /** Catalog has expected cert(s) but none could be read for the package. */
    OBSERVED_CERT_MISSING,

    /** No expected cert is catalogued and [MissingExpectedCertPolicy.REFUSE] is set. */
    EXPECTED_CERT_MISSING,
}

/**
 * Documented node signing identities plus every [KnownFleet] Android
 * package.
 *
 * This catalogue is **partial**. Fail-closed
 * ([MissingExpectedCertPolicy.REFUSE]) should be enabled only once
 * remaining members and verified release pins are catalogued.
 * Do not invent fingerprints. Do not catalogue victorious-flash
 * (debug cert is non-deterministic until they land a fixed keystore).
 */
object KnownNodeSigningCatalog {
    /** DroidCommand AI debug keystore (`docs/SIGNING.md`). Enforceable. */
    const val DROIDCOMMAND_AI_DEBUG_SHA256 =
        "88:00:51:A6:D9:5D:38:DD:8D:81:E1:93:1E:17:05:48:03:87:D5:93:C3:09:43:53:99:68:E6:EF:11:A0:C8:4D"

    /**
     * Victorious Mesh debug keystore
     * (`Victorious93/victorious-mesh` `android/keystore/victorious-mesh-debug.jks`).
     * Enforceable.
     */
    const val VICTORIOUS_MESH_DEBUG_SHA256 =
        "F4:C9:25:B8:E8:0B:55:AB:83:E6:04:13:EE:49:86:57:DF:85:03:BB:30:D8:1F:9F:17:43:F2:2B:F5:36:B9:F4"

    /** Pentest-Swarm-AI committed debug signing cert. Enforceable. */
    const val PENTEST_SWARM_DEBUG_SHA256 =
        "5E:3A:B1:23:1F:7E:DA:18:78:B8:FF:D8:9E:AC:B1:F7:2E:EB:66:AF:3D:BA:2E:01:33:51:24:68:42:23:30:C7"

    /** EDL-Commander committed debug signing cert. Enforceable. */
    const val EDL_COMMANDER_DEBUG_SHA256 =
        "E5:60:B6:92:BD:E6:51:9E:B7:CB:B5:2C:E4:DC:91:5A:19:E4:41:A0:1A:5D:03:68:82:71:AC:99:78:8A:58:C8"

    /** HackerAI-Commander committed debug signing cert. Enforceable. */
    const val HACKERAI_DEBUG_SHA256 =
        "AF:1C:8F:C5:44:9F:58:4A:46:C8:96:CC:C9:7F:35:B0:7B:35:7E:96:EE:66:5F:3A:BF:CB:0D:28:4D:F5:23:55"

    /** rootforge-os debug package `ai.rootforge.os.debug`. Enforceable. No release key yet. */
    const val ROOTFORGE_DEBUG_SHA256 =
        "77:7A:C7:00:87:05:A8:B4:B8:93:AC:EF:0E:03:E9:1C:7F:49:52:C9:52:FE:B2:6E:06:D7:90:B9:14:86:64:6F"

    /** Victorious Keyboard debug (`com.victorious.keyboard` / `.debug`). Enforceable. No release key yet. */
    const val KEYBOARD_DEBUG_SHA256 =
        "47:1E:AD:6B:3F:96:7E:F3:C8:63:EF:49:08:74:8B:3B:E3:63:F9:1D:75:0B:FD:B1:E9:28:05:BC:F1:6B:AF:4F"

    /**
     * Victorious Browser debug (`com.victorious.browser`). Enforceable.
     * RELEASE is [planned/unverified]: own key (NOT shared fleet 9E:A7),
     * CI-only, cert TBD — do not invent a fingerprint.
     */
    const val VICTORIOUS_BROWSER_DEBUG_SHA256 =
        "2D:5D:9E:8B:53:8E:78:51:3D:B5:41:F4:DF:FD:67:BE:27:FF:EC:3F:37:EB:1A:D4:8D:77:83:AE:59:27:C3:E1"

    /** VictorSuite / Termux (`com.termux`) committed debug signing cert. Enforceable. */
    const val VICTORSUITE_DEBUG_SHA256 =
        "67:17:C5:5A:BE:9E:0D:F8:C6:03:C9:A0:F3:F3:67:B6:CA:BD:B0:1F:3E:58:3A:4F:F6:44:E5:DF:3D:4C:31:D3"

    /**
     * VictorSuite **own** RELEASE cert (not the shared fleet key).
     * CI-only but authoritative. Enforceable for `com.termux` only.
     */
    const val VICTORSUITE_RELEASE_SHA256 =
        "8C:BF:09:AA:5D:0D:24:6B:5A:97:C9:5D:2E:70:DC:0C:5F:5B:D3:33:8C:39:9A:97:88:91:09:A1:0D:CE:19:80"

    /**
     * Shared fleet RELEASE SHA-256.
     *
     * FIRSTHAND VERIFIED for HackerAI-Commander only (v0.1.0 APK signer
     * verified in CI) — [HACKERAI] lists it in [expectedCerts].
     * For DroidCommand-AI, Pentest-Swarm-AI, and EDL-Commander the same
     * fingerprint remains **[planned / unverified]** and is **not**
     * wired into those members' expected cert lists.
     */
    const val SHARED_FLEET_RELEASE_SHA256 =
        "9E:A7:B3:14:94:5B:0A:62:97:15:6F:F7:06:E7:0C:0A:B1:B7:24:DE:06:D5:09:9C:A5:12:8A:B4:43:F7:2D:8D"

    /**
     * File-Manager DEBUG, supplied by the fleet custodian.
     *
     * **[unstable / CI-secrets] — not enforced.** Not committed; CI
     * injects from secrets and falls back to the generic Android debug
     * key without them. Only stable when secrets are present. No
     * release key yet. Putting this in [expectedCerts] would refuse
     * the generic-debug fallback, so File-Manager packages stay
     * fail-open until the keystore is fixed.
     */
    const val FILE_MANAGER_DEBUG_UNSTABLE_CI_SECRETS =
        "8C:1F:E3:C9:E5:34:F2:71:05:C0:68:8F:45:28:D7:7E:47:11:AE:0A:79:DD:08:D1:E8:59:30:B9:E9:48:85:F2"

    /**
     * DroidCommand AI hub debug identity (`ai.droidcommand.app`).
     * Shared fleet RELEASE [SHARED_FLEET_RELEASE_SHA256] is
     * [planned/unverified] for this package — not listed in
     * [expectedCerts].
     */
    val DROIDCOMMAND_AI_DEBUG = NodeSigningIdentity(
        packageName = "ai.droidcommand.app",
        expectedCerts = listOf(SigningCertSha256.parseOrThrow(DROIDCOMMAND_AI_DEBUG_SHA256)),
        memberId = "droidcommand",
        integrationClass = null,
        notes = "DroidCommand AI debug keystore (docs/SIGNING.md). Enforceable. " +
            "Shared fleet RELEASE 9E:A7:… is [planned/unverified] — not enforced.",
    )

    /**
     * Victorious Mesh debug identity. Release is `[Unknown]` — not
     * invented here.
     *
     * Mesh is [KnownFleet.MESH], EXTERNAL_NODE, wiring order 8, PLANNED.
     * Fleet IPC Messenger bind is still TODO.
     */
    val VICTORIOUS_MESH_DEBUG = NodeSigningIdentity(
        packageName = "com.victorious.mesh",
        expectedCerts = listOf(SigningCertSha256.parseOrThrow(VICTORIOUS_MESH_DEBUG_SHA256)),
        memberId = "mesh",
        integrationClass = FleetIntegrationClass.EXTERNAL_NODE,
        notes = "Victorious Mesh debug keystore. Enforceable. Release fingerprint is [Unknown]. " +
            "KnownFleet.MESH (EXTERNAL_NODE, wiring order 8, PLANNED). " +
            "Never DependencyGuard, never a shared signing key. Fleet IPC Messenger is TODO on their side.",
    )

    /**
     * Victorious Browser debug identity. EXTERNAL_NODE, not a
     * [KnownFleet] wiring-order member. RELEASE is own-key
     * [planned/unverified] (CI-only, cert TBD) — not [SHARED_FLEET_RELEASE_SHA256]
     * and not listed in [expectedCerts].
     */
    val VICTORIOUS_BROWSER_DEBUG = NodeSigningIdentity(
        packageName = "com.victorious.browser",
        expectedCerts = listOf(SigningCertSha256.parseOrThrow(VICTORIOUS_BROWSER_DEBUG_SHA256)),
        memberId = "browser",
        integrationClass = FleetIntegrationClass.EXTERNAL_NODE,
        notes = "Victorious Browser debug. Enforceable. RELEASE is [planned/unverified] " +
            "(own key, NOT shared fleet 9E:A7, CI-only, cert TBD). " +
            "Never DependencyGuard, never a shared signing key. Not a KnownFleet wiring-order member.",
    )

    /**
     * HackerAI-Commander (`ai.hackerai.companion`). Debug is
     * committed; RELEASE [SHARED_FLEET_RELEASE_SHA256] is FIRSTHAND
     * VERIFIED (v0.1.0 APK signer in CI). This is the **only** member
     * whose shared-fleet release cert is enforced.
     */
    val HACKERAI = NodeSigningIdentity(
        packageName = "ai.hackerai.companion",
        expectedCerts = listOf(
            SigningCertSha256.parseOrThrow(HACKERAI_DEBUG_SHA256),
            SigningCertSha256.parseOrThrow(SHARED_FLEET_RELEASE_SHA256),
        ),
        memberId = "hackerai",
        integrationClass = FleetIntegrationClass.BOUND_COMPANION,
        notes = "HackerAI-Commander debug + firsthand-verified RELEASE 9E:A7:… (v0.1.0). " +
            "Enforceable. Not one of the eight KnownFleet members.",
    )

    /**
     * Keyboard `.bench` has no custodian-supplied cert. Extra overrides
     * [KnownFleet.KEYBOARD]'s shared expected list so the benchmark
     * package stays fail-open.
     */
    val KEYBOARD_BENCH = NodeSigningIdentity(
        packageName = "com.victorious.keyboard.bench",
        expectedCerts = emptyList(),
        memberId = "keyboard",
        integrationClass = FleetIntegrationClass.EXTERNAL_NODE,
        notes = "[Unknown] — Keyboard debug cert applies to com.victorious.keyboard " +
            "and .debug only. .bench is uncatalogued (fail-open).",
    )

    val EXTRAS: List<NodeSigningIdentity> = listOf(
        DROIDCOMMAND_AI_DEBUG,
        VICTORIOUS_MESH_DEBUG,
        VICTORIOUS_BROWSER_DEBUG,
        HACKERAI,
        KEYBOARD_BENCH,
    )

    fun fromFleet(members: List<FleetMemberDescriptor> = KnownFleet.ALL): List<NodeSigningIdentity> =
        members.flatMap { member ->
            member.androidPackages.map { pkg ->
                NodeSigningIdentity(
                    packageName = pkg,
                    expectedCerts = member.expectedSigningCertSha256.map { SigningCertSha256.parseOrThrow(it) },
                    memberId = member.id,
                    integrationClass = member.integrationClass,
                    notes = if (member.expectedSigningCertSha256.isEmpty()) {
                        "[Unknown] — no catalogued signing cert SHA-256 for ${member.displayName}"
                    } else {
                        "Catalogued on KnownFleet.${member.id}"
                    },
                )
            }
        }

    fun all(members: List<FleetMemberDescriptor> = KnownFleet.ALL): List<NodeSigningIdentity> {
        val extras = EXTRAS
        val extraPackages = extras.map { it.packageName }.toSet()
        return extras + fromFleet(members).filter { it.packageName !in extraPackages }
    }

    fun identityFor(
        packageName: String,
        members: List<FleetMemberDescriptor> = KnownFleet.ALL,
    ): NodeSigningIdentity? = all(members).find { it.packageName == packageName }
}

/**
 * Pure comparison. Callers supply already-resolved fingerprints (or raw
 * cert bytes via [SigningCertObserver]); this class never touches
 * Android's PackageManager.
 *
 * Allow when any observed fingerprint is in the expected set. Refuse on
 * mismatch or missing-observed-when-required. Missing-expected follows
 * [missingExpectedPolicy].
 */
class NodeSignatureVerifier(
    private val identities: List<NodeSigningIdentity> = KnownNodeSigningCatalog.all(),
    private val missingExpectedPolicy: MissingExpectedCertPolicy = MissingExpectedCertPolicy.ALLOW,
) {
    fun verify(packageName: String, observedFingerprints: Collection<String>): NodeSignatureDecision {
        val observed = observedFingerprints.mapNotNull { SigningCertSha256.parse(it) }.distinct()
        val identity = identities.find { it.packageName == packageName }
        val expected = identity?.expectedCerts.orEmpty()

        if (expected.isEmpty()) {
            return when (missingExpectedPolicy) {
                MissingExpectedCertPolicy.ALLOW -> NodeSignatureDecision.Allowed(
                    packageName = packageName,
                    matchedFingerprint = null,
                    message = "Allowed '$packageName': no catalogued signing cert SHA-256 " +
                        "([Unknown]); default missing-expected policy is ALLOW so existing " +
                        "flows for uncatalogued members are not blocked. Set " +
                        "${MissingExpectedCertPolicy.ENV_NAME}=refuse for fail-closed.",
                )
                MissingExpectedCertPolicy.REFUSE -> NodeSignatureDecision.Refused(
                    packageName = packageName,
                    kind = NodeSignatureRefusal.EXPECTED_CERT_MISSING,
                    message = "Refusing '$packageName': no catalogued signing cert SHA-256 " +
                        "and missing-expected policy is fail-closed (REFUSE).",
                    observed = observed.map { it.canonical },
                    expected = emptyList(),
                )
            }
        }

        if (observed.isEmpty()) {
            return NodeSignatureDecision.Refused(
                packageName = packageName,
                kind = NodeSignatureRefusal.OBSERVED_CERT_MISSING,
                message = "Refusing '$packageName': catalogued signing cert SHA-256 is required " +
                    "but none was observed (package not installed, or signing certificates " +
                    "could not be read).",
                observed = emptyList(),
                expected = expected.map { it.canonical },
            )
        }

        val match = observed.firstOrNull { got -> expected.any { it.canonical == got.canonical } }
        if (match == null) {
            return NodeSignatureDecision.Refused(
                packageName = packageName,
                kind = NodeSignatureRefusal.CERT_MISMATCH,
                message = "Refusing '$packageName': signing cert SHA-256 does not match the " +
                    "catalogued value(s). observed=${observed.joinToString { it.canonical }} " +
                    "expected=${expected.joinToString { it.canonical }}",
                observed = observed.map { it.canonical },
                expected = expected.map { it.canonical },
            )
        }
        return NodeSignatureDecision.Allowed(
            packageName = packageName,
            matchedFingerprint = match.canonical,
            message = "Allowed '$packageName': signing cert matches catalogued fingerprint ${match.canonical}.",
        )
    }
}

/**
 * Resolves the installed signing certificates for a package. Android
 * implementations wrap PackageManager; tests supply a map.
 *
 * Returns raw certificate bytes (the DER-encoded signing cert), not a
 * precomputed digest, so SHA-256 stays in [SigningCertSha256].
 */
fun interface SigningCertObserver {
    fun certificateBytes(packageName: String): List<ByteArray>
}

/**
 * Controller-side gate: resolve certs, then [NodeSignatureVerifier.verify].
 * Call this **before** bindService / Messenger bind / commanding a node.
 * A [NodeSignatureDecision.Refused] result must not proceed.
 *
 * Does not replace [DependencyGuard], signature permissions, Kali/pentest
 * authorization, or any other existing gate — it only adds a check.
 */
class NodeSignatureGuard(
    private val observer: SigningCertObserver,
    private val verifier: NodeSignatureVerifier = NodeSignatureVerifier(),
) {
    fun check(packageName: String): NodeSignatureDecision {
        val observed = observer.certificateBytes(packageName).map { bytes ->
            SigningCertSha256.fromCertificateBytes(bytes).canonical
        }
        return verifier.verify(packageName, observed)
    }
}
