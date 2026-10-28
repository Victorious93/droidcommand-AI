package ai.droidcommand.remotecontrol

import ai.droidcommand.security.KnownNodeSigningCatalog
import ai.droidcommand.security.MissingExpectedCertPolicy
import ai.droidcommand.security.NodeSignatureDecision
import ai.droidcommand.security.NodeSignatureRefusal
import ai.droidcommand.security.NodeSignatureVerifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrustPipelineTest {
    private val launchPackage = "com.victorious.launch"
    private val launchCert = KnownNodeSigningCatalog.VICTOR_LAUNCH_DEBUG_SHA256
    private val attacker =
        "00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF"
    private val revokedLaunch =
        "1B:06:A8:9E:7F:F5:BA:21:C9:A4:F8:F1:F0:4F:95:E5:62:78:A1:22:18:AD:37:33:28:CE:E7:83:1D:4F:BA:CA"

    @Test
    fun `known catalog cert plus matching source is accepted`() {
        assertNull(
            TrustPipeline.pairingDecision(
                launchSource(certSha256 = launchCert),
                launchConfig(certSha256 = null),
            ),
        )
        assertNull(
            TrustPipeline.pairingDecision(
                launchSource(certSha256 = launchCert),
                launchConfig(certSha256 = launchCert),
            ),
        )
    }

    @Test
    fun `known catalog cert plus blank source is rejected`() {
        assertEquals(
            RemoteErrorCategory.UNAUTHORIZED,
            TrustPipeline.pairingDecision(
                launchSource(certSha256 = null),
                launchConfig(certSha256 = null),
            ),
        )
        assertEquals(
            RemoteErrorCategory.UNAUTHORIZED,
            TrustPipeline.pairingDecision(
                launchSource(certSha256 = "  "),
                launchConfig(certSha256 = launchCert),
            ),
        )
    }

    @Test
    fun `known catalog cert plus wrong source is rejected`() {
        assertEquals(
            RemoteErrorCategory.UNAUTHORIZED,
            TrustPipeline.pairingDecision(
                launchSource(certSha256 = attacker),
                launchConfig(certSha256 = null),
            ),
        )
        assertEquals(
            RemoteErrorCategory.UNAUTHORIZED,
            TrustPipeline.pairingDecision(
                launchSource(certSha256 = revokedLaunch),
                launchConfig(certSha256 = launchCert),
            ),
        )
    }

    @Test
    fun `unknown package plus blank source stays pairing-unpinned`() {
        val config = inboundConfig(
            pairings = listOf(
                DevicePairing(
                    sourceNodeRef = "unknown-node",
                    packageName = "ai.not.a.fleet.member",
                    certSha256 = null,
                ),
            ),
        )
        val source = SourceIdentity(
            sourceNodeRef = "unknown-node",
            packageName = "ai.not.a.fleet.member",
            certSha256 = null,
        )
        assertNull(TrustPipeline.pairingDecision(source, config))
        assertTrue(cataloguedExpectedCerts("ai.not.a.fleet.member").isEmpty())
    }

    @Test
    fun `unknown package plus blank is still governed by MissingExpectedCertPolicy`() {
        assertEquals(MissingExpectedCertPolicy.ALLOW, MissingExpectedCertPolicy.fromEnvironment { null })
        val allow = NodeSignatureVerifier().verify("ai.not.a.fleet.member", emptyList())
        assertIs<NodeSignatureDecision.Allowed>(allow)
        val refuse = NodeSignatureVerifier(missingExpectedPolicy = MissingExpectedCertPolicy.REFUSE)
            .verify("ai.not.a.fleet.member", emptyList())
        assertEquals(
            NodeSignatureRefusal.EXPECTED_CERT_MISSING,
            (refuse as NodeSignatureDecision.Refused).kind,
        )
    }

    @Test
    fun `pairing pin without catalog still rejects a blank source`() {
        val pin = "aa".repeat(32)
        val config = inboundConfig(pairings = listOf(pairing(certSha256 = pin)))
        assertEquals(
            RemoteErrorCategory.UNAUTHORIZED,
            TrustPipeline.pairingDecision(source().copy(certSha256 = null), config),
        )
        assertNull(
            TrustPipeline.pairingDecision(source().copy(certSha256 = pin), config),
        )
    }

    @Test
    fun `VictorSuite suggestedPairing carries the catalogued debug pin`() {
        val pairing = VictorSuiteRegistry.TARGET.suggestedPairing("suite-node")
        assertEquals("com.termux", pairing.packageName)
        assertEquals(KnownNodeSigningCatalog.VICTORSUITE_DEBUG_SHA256, pairing.certSha256)
        assertEquals(
            listOf(
                KnownNodeSigningCatalog.VICTORSUITE_DEBUG_SHA256,
                KnownNodeSigningCatalog.VICTORSUITE_RELEASE_SHA256,
            ),
            cataloguedExpectedCerts("com.termux").map { it.canonical },
        )
    }

    @Test
    fun `VictorSuite catalog accepts either documented expected cert`() {
        val config = inboundConfig(
            pairings = listOf(
                DevicePairing(
                    sourceNodeRef = "suite-node",
                    packageName = "com.termux",
                    certSha256 = null,
                ),
            ),
        )
        assertNull(
            TrustPipeline.pairingDecision(
                SourceIdentity(
                    sourceNodeRef = "suite-node",
                    packageName = "com.termux",
                    certSha256 = KnownNodeSigningCatalog.VICTORSUITE_DEBUG_SHA256,
                ),
                config,
            ),
        )
        assertNull(
            TrustPipeline.pairingDecision(
                SourceIdentity(
                    sourceNodeRef = "suite-node",
                    packageName = "com.termux",
                    certSha256 = KnownNodeSigningCatalog.VICTORSUITE_RELEASE_SHA256,
                ),
                config,
            ),
        )
        assertEquals(
            RemoteErrorCategory.UNAUTHORIZED,
            TrustPipeline.pairingDecision(
                SourceIdentity(
                    sourceNodeRef = "suite-node",
                    packageName = "com.termux",
                    certSha256 = attacker,
                ),
                config,
            ),
        )
    }

    @Test
    fun `UNENFORCED documented certs are not copied onto suggestedPairing`() {
        assertNull(VictorSmsRegistry.suggestedPairing(TEST_SOURCE).certSha256)
        assertNull(KycAuditLabRegistry.suggestedPairing("kyc-node").certSha256)
        assertNull(VictorSpoofRegistry.suggestedPairing("spoof-node").certSha256)
        assertTrue(cataloguedExpectedCerts(VictorSmsRegistry.PACKAGE).isEmpty())
        assertTrue(cataloguedExpectedCerts(KycAuditLabRegistry.PACKAGE).isEmpty())
        assertTrue(cataloguedExpectedCerts(VictorSpoofRegistry.PACKAGE).isEmpty())
        assertNotNull(KnownNodeSigningCatalog.VICTOR_SMS_DEBUG_SHA256_UNENFORCED)
        assertNotNull(KnownNodeSigningCatalog.KYC_AUDITLAB_DEBUG_SHA256_UNENFORCED)
    }

    @Test
    fun `VictorLaunch catalog pin stays the rotated enforced fingerprint`() {
        assertEquals(
            "B9:9A:AF:E2:C1:41:8B:5D:64:AA:0D:4B:AC:61:FC:F7:A7:BA:81:44:35:E0:EF:89:E8:AA:55:BE:56:4E:4A:69",
            launchCert,
        )
        assertEquals(listOf(launchCert), cataloguedExpectedCerts(launchPackage).map { it.canonical })
        assertEquals(launchCert, suggestedCatalogPin(launchPackage))
    }

    private fun launchConfig(certSha256: String?) = inboundConfig(
        pairings = listOf(
            DevicePairing(
                sourceNodeRef = "launch-node",
                packageName = launchPackage,
                certSha256 = certSha256,
            ),
        ),
    )

    private fun launchSource(certSha256: String?) = SourceIdentity(
        sourceNodeRef = "launch-node",
        packageName = launchPackage,
        certSha256 = certSha256,
    )
}
