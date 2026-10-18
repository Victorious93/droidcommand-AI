package ai.droidcommand.security

import java.security.MessageDigest

/**
 * SHA-256 of an Android app signing certificate, stored as uppercase
 * colon-separated hex (`AA:BB:…`). Comparison ignores case and separators.
 *
 * This is the controller-side pin for a fleet NODE's signing identity.
 * It is not a shared fleet keystore and is not [DependencyGuard].
 */
data class SigningCertSha256 internal constructor(val canonical: String) {
    fun matches(other: String): Boolean = parse(other)?.canonical == canonical

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
 * still `[Unknown]` (Pentest, RootForge, VictorSuite, EDL, KYC, File
 * Manager, Keyboard, HackerAI, …) keep working. Flip to [REFUSE] via
 * [fromEnvironment] (`DROIDCOMMAND_NODE_CERT_MISSING_EXPECTED=refuse`)
 * once those fingerprints are catalogued.
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
 * package. Only [VICTORIOUS_MESH] currently has a real fingerprint;
 * everyone else is `[Unknown]`.
 */
object KnownNodeSigningCatalog {
    /**
     * Victorious Mesh debug identity, documented against the committed
     * debug keystore in `Victorious93/victorious-mesh`
     * (`android/keystore/victorious-mesh-debug.jks`). Release is
     * `[Unknown]` — not invented here.
     *
     * Mesh is an EXTERNAL_NODE. It is **not** yet a [KnownFleet]
     * wiring-order member (their Fleet IPC Messenger is still TODO).
     * The controller still pins this package so a bind/command of
     * `com.victorious.mesh` cannot silently accept a substituted APK.
     */
    val VICTORIOUS_MESH_DEBUG = NodeSigningIdentity(
        packageName = "com.victorious.mesh",
        expectedCerts = listOf(
            SigningCertSha256.parseOrThrow(
                "F4:C9:25:B8:E8:0B:55:AB:83:E6:04:13:EE:49:86:57:DF:85:03:BB:30:D8:1F:9F:17:43:F2:2B:F5:36:B9:F4",
            ),
        ),
        memberId = "mesh",
        integrationClass = FleetIntegrationClass.EXTERNAL_NODE,
        notes = "Victorious Mesh debug keystore. Release fingerprint is [Unknown]. " +
            "Never DependencyGuard, never a shared signing key. Fleet IPC Messenger is TODO on their side.",
    )

    /**
     * HackerAI is a bound companion in `KnownCompanions`, not one of the
     * seven [KnownFleet] members. Cert is `[Unknown]` — not invented.
     */
    val HACKERAI = NodeSigningIdentity(
        packageName = "ai.hackerai.companion",
        expectedCerts = emptyList(),
        memberId = "hackerai",
        integrationClass = FleetIntegrationClass.BOUND_COMPANION,
        notes = "[Unknown] — HackerAI is a KnownCompanions bound companion, not one of the seven. " +
            "Signing cert SHA-256 is not catalogued.",
    )

    val EXTRAS: List<NodeSigningIdentity> = listOf(VICTORIOUS_MESH_DEBUG, HACKERAI)

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
