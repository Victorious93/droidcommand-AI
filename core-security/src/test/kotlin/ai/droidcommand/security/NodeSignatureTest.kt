package ai.droidcommand.security

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NodeSignatureTest {
    private val meshDebug =
        "F4:C9:25:B8:E8:0B:55:AB:83:E6:04:13:EE:49:86:57:DF:85:03:BB:30:D8:1F:9F:17:43:F2:2B:F5:36:B9:F4"
    private val meshReleasePlaceholder =
        "11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00"
    private val attacker =
        "00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF"

    @Test
    fun `cert match is allowed`() {
        val decision = NodeSignatureVerifier().verify("com.victorious.mesh", listOf(meshDebug))
        val allowed = assertIs<NodeSignatureDecision.Allowed>(decision)
        assertEquals(meshDebug, allowed.matchedFingerprint)
        assertTrue(allowed.message.contains("matches catalogued fingerprint"))
    }

    @Test
    fun `fingerprint comparison is case-insensitive and ignores separators`() {
        val compacted = meshDebug.lowercase().replace(":", "")
        val decision = NodeSignatureVerifier().verify("com.victorious.mesh", listOf(compacted))
        assertIs<NodeSignatureDecision.Allowed>(decision)
        assertEquals(meshDebug, decision.matchedFingerprint)
    }

    @Test
    fun `mismatch is refused`() {
        val decision = NodeSignatureVerifier().verify("com.victorious.mesh", listOf(attacker))
        val refused = assertIs<NodeSignatureDecision.Refused>(decision)
        assertEquals(NodeSignatureRefusal.CERT_MISMATCH, refused.kind)
        assertTrue(refused.message.contains("does not match"))
        assertEquals(listOf(attacker), refused.observed)
        assertEquals(listOf(meshDebug), refused.expected)
    }

    @Test
    fun `missing observed cert is refused when expected certs are catalogued`() {
        val decision = NodeSignatureVerifier().verify("com.victorious.mesh", emptyList())
        val refused = assertIs<NodeSignatureDecision.Refused>(decision)
        assertEquals(NodeSignatureRefusal.OBSERVED_CERT_MISSING, refused.kind)
        assertTrue(refused.message.contains("none was observed"))
    }

    @Test
    fun `uncatalogued member is allowed under the documented fail-open default`() {
        val decision = NodeSignatureVerifier().verify("ai.victorious.filemanager", listOf(attacker))
        val allowed = assertIs<NodeSignatureDecision.Allowed>(decision)
        assertNull(allowed.matchedFingerprint)
        assertTrue(allowed.message.contains("no catalogued signing cert"))
        assertTrue(allowed.message.contains("ALLOW"))
        assertIs<NodeSignatureDecision.Allowed>(
            NodeSignatureVerifier().verify("com.victorious.keyboard.bench", listOf(attacker)),
        )
    }

    @Test
    fun `missing expected cert is refused when policy is fail-closed`() {
        val verifier = NodeSignatureVerifier(missingExpectedPolicy = MissingExpectedCertPolicy.REFUSE)
        val decision = verifier.verify("ai.victorious.filemanager", listOf(attacker))
        val refused = assertIs<NodeSignatureDecision.Refused>(decision)
        assertEquals(NodeSignatureRefusal.EXPECTED_CERT_MISSING, refused.kind)
        assertTrue(refused.message.contains("fail-closed"))
    }

    @Test
    fun `unknown package with no catalog entry follows missing-expected policy`() {
        val allow = NodeSignatureVerifier().verify("ai.not.a.fleet.member", emptyList())
        assertIs<NodeSignatureDecision.Allowed>(allow)
        val refuse = NodeSignatureVerifier(missingExpectedPolicy = MissingExpectedCertPolicy.REFUSE)
            .verify("ai.not.a.fleet.member", emptyList())
        assertEquals(NodeSignatureRefusal.EXPECTED_CERT_MISSING, (refuse as NodeSignatureDecision.Refused).kind)
    }

    @Test
    fun `debug and release accepted certs both pass`() {
        val identity = NodeSigningIdentity(
            packageName = "com.example.node",
            expectedCerts = listOf(
                SigningCertSha256.parseOrThrow(meshDebug),
                SigningCertSha256.parseOrThrow(meshReleasePlaceholder),
            ),
            memberId = "example",
            integrationClass = FleetIntegrationClass.EXTERNAL_NODE,
            notes = "debug+release",
        )
        val verifier = NodeSignatureVerifier(identities = listOf(identity))
        assertIs<NodeSignatureDecision.Allowed>(verifier.verify("com.example.node", listOf(meshDebug)))
        assertIs<NodeSignatureDecision.Allowed>(
            verifier.verify("com.example.node", listOf(meshReleasePlaceholder)),
        )
        val mismatch = verifier.verify("com.example.node", listOf(attacker))
        assertEquals(NodeSignatureRefusal.CERT_MISMATCH, (mismatch as NodeSignatureDecision.Refused).kind)
    }

    @Test
    fun `catalogued member plus matching cert is allowed`() {
        val verifier = NodeSignatureVerifier()
        val cases = listOf(
            "ai.droidcommand.app" to KnownNodeSigningCatalog.DROIDCOMMAND_AI_DEBUG_SHA256,
            "com.victorious.mesh" to KnownNodeSigningCatalog.VICTORIOUS_MESH_DEBUG_SHA256,
            "com.victorious.browser" to KnownNodeSigningCatalog.VICTORIOUS_BROWSER_DEBUG_SHA256,
            "ai.pentestswarm.companion" to KnownNodeSigningCatalog.PENTEST_SWARM_DEBUG_SHA256,
            "ai.edlcommander.companion" to KnownNodeSigningCatalog.EDL_COMMANDER_DEBUG_SHA256,
            "ai.hackerai.companion" to KnownNodeSigningCatalog.HACKERAI_DEBUG_SHA256,
            "ai.hackerai.companion" to KnownNodeSigningCatalog.SHARED_FLEET_RELEASE_SHA256,
            "ai.rootforge.os.debug" to KnownNodeSigningCatalog.ROOTFORGE_DEBUG_SHA256,
            "com.victorious.keyboard" to KnownNodeSigningCatalog.KEYBOARD_DEBUG_SHA256,
            "com.victorious.keyboard.debug" to KnownNodeSigningCatalog.KEYBOARD_DEBUG_SHA256,
            "com.termux" to KnownNodeSigningCatalog.VICTORSUITE_DEBUG_SHA256,
            "com.termux" to KnownNodeSigningCatalog.VICTORSUITE_RELEASE_SHA256,
        )
        for ((pkg, fp) in cases) {
            val allowed = assertIs<NodeSignatureDecision.Allowed>(verifier.verify(pkg, listOf(fp)), pkg)
            assertEquals(fp, allowed.matchedFingerprint, pkg)
        }
    }

    @Test
    fun `catalogued member plus mismatched cert is refused`() {
        val verifier = NodeSignatureVerifier()
        for (pkg in listOf(
            "ai.droidcommand.app",
            "com.victorious.mesh",
            "com.victorious.browser",
            "ai.pentestswarm.companion",
            "ai.edlcommander.companion",
            "ai.hackerai.companion",
            "ai.rootforge.os.debug",
            "com.victorious.keyboard",
            "com.termux",
        )) {
            val refused = assertIs<NodeSignatureDecision.Refused>(verifier.verify(pkg, listOf(attacker)), pkg)
            assertEquals(NodeSignatureRefusal.CERT_MISMATCH, refused.kind, pkg)
        }
    }

    @Test
    fun `KnownFleet android packages and which members are still uncatalogued`() {
        assertEquals(listOf("ai.pentestswarm.companion"), KnownFleet.PENTEST_SWARM.androidPackages)
        assertEquals(listOf("ai.rootforge.os.debug"), KnownFleet.ROOTFORGE.androidPackages)
        assertEquals(listOf("com.termux"), KnownFleet.VICTORSUITE.androidPackages)
        assertEquals(
            listOf("ai.victorious.filemanager", "ai.victorious.filemanager.debug"),
            KnownFleet.FILE_MANAGER.androidPackages,
        )
        assertEquals(
            listOf(
                "com.victorious.keyboard",
                "com.victorious.keyboard.debug",
                "com.victorious.keyboard.bench",
            ),
            KnownFleet.KEYBOARD.androidPackages,
        )
        assertTrue(KnownFleet.KYC_AUDITLAB.androidPackages.isEmpty())
        assertTrue(KnownFleet.FILE_MANAGER.expectedSigningCertSha256.isEmpty())
        assertEquals("[Unknown]", KnownFleet.FILE_MANAGER.signingCertLabel())
        assertEquals("[Unknown]", KnownFleet.KYC_AUDITLAB.signingCertLabel())
    }

    @Test
    fun `descriptor construction rejects a garbage fingerprint rather than storing it`() {
        assertFailsWith<IllegalArgumentException> {
            KnownFleet.FILE_MANAGER.copy(expectedSigningCertSha256 = listOf("not-a-fingerprint"))
        }
    }

    @Test
    fun `HackerAI enforces debug and the firsthand-verified shared-fleet release`() {
        val identity = KnownNodeSigningCatalog.identityFor("ai.hackerai.companion")
        assertEquals("hackerai", identity?.memberId)
        assertEquals(
            listOf(
                KnownNodeSigningCatalog.HACKERAI_DEBUG_SHA256,
                KnownNodeSigningCatalog.SHARED_FLEET_RELEASE_SHA256,
            ),
            identity?.expectedCerts?.map { it.canonical },
        )
    }

    @Test
    fun `shared fleet release is not enforced for DCA Pentest or EDL`() {
        val planned = SigningCertSha256.parseOrThrow(KnownNodeSigningCatalog.SHARED_FLEET_RELEASE_SHA256)
        for (pkg in listOf(
            "ai.droidcommand.app",
            "ai.pentestswarm.companion",
            "ai.edlcommander.companion",
            "com.victorious.browser",
        )) {
            val certs = KnownNodeSigningCatalog.identityFor(pkg)?.expectedCerts.orEmpty()
            assertTrue(planned !in certs, pkg)
        }
        assertTrue(
            KnownFleet.PENTEST_SWARM.expectedSigningCertSha256.none {
                SigningCertSha256.parseOrThrow(it) == planned
            },
        )
        assertTrue(
            KnownFleet.EDL_COMMANDER.expectedSigningCertSha256.none {
                SigningCertSha256.parseOrThrow(it) == planned
            },
        )
    }

    @Test
    fun `File-Manager unstable debug cert is documented but not enforced`() {
        val unstable = SigningCertSha256.parseOrThrow(
            KnownNodeSigningCatalog.FILE_MANAGER_DEBUG_UNSTABLE_CI_SECRETS,
        )
        for (pkg in listOf("ai.victorious.filemanager", "ai.victorious.filemanager.debug")) {
            val identity = KnownNodeSigningCatalog.identityFor(pkg)
            assertTrue(identity?.expectedCerts.isNullOrEmpty(), pkg)
            assertIs<NodeSignatureDecision.Allowed>(
                NodeSignatureVerifier().verify(pkg, listOf(unstable.canonical)),
            )
        }
        assertTrue(
            KnownNodeSigningCatalog.all().none { identity ->
                unstable in identity.expectedCerts
            },
        )
    }

    @Test
    fun `victorious-flash is not catalogued`() {
        assertTrue(
            KnownNodeSigningCatalog.all().none { it.packageName.contains("flash") },
        )
    }

    @Test
    fun `Victorious Browser debug is enforced and release stays planned`() {
        val identity = KnownNodeSigningCatalog.VICTORIOUS_BROWSER_DEBUG
        assertEquals("com.victorious.browser", identity.packageName)
        assertEquals(FleetIntegrationClass.EXTERNAL_NODE, identity.integrationClass)
        assertEquals(
            listOf(KnownNodeSigningCatalog.VICTORIOUS_BROWSER_DEBUG_SHA256),
            identity.expectedCerts.map { it.canonical },
        )
        assertEquals(identity, KnownNodeSigningCatalog.identityFor("com.victorious.browser"))
        assertTrue(identity.notes.contains("[planned/unverified]"))
        assertTrue(identity.notes.contains("NOT shared fleet"))
        assertTrue(KnownFleet.ALL.none { "browser" in it.androidPackages })
    }

    @Test
    fun `Victorious Mesh debug fingerprint is the documented value`() {
        val identity = KnownNodeSigningCatalog.VICTORIOUS_MESH_DEBUG
        assertEquals("com.victorious.mesh", identity.packageName)
        assertEquals(FleetIntegrationClass.EXTERNAL_NODE, identity.integrationClass)
        assertEquals(listOf(meshDebug), identity.expectedCerts.map { it.canonical })
        assertEquals(identity, KnownNodeSigningCatalog.identityFor("com.victorious.mesh"))
    }

    @Test
    fun `missing-expected policy from environment defaults to ALLOW`() {
        assertEquals(
            MissingExpectedCertPolicy.ALLOW,
            MissingExpectedCertPolicy.fromEnvironment { null },
        )
        assertEquals(
            MissingExpectedCertPolicy.ALLOW,
            MissingExpectedCertPolicy.fromEnvironment { "allow" },
        )
        assertEquals(
            MissingExpectedCertPolicy.REFUSE,
            MissingExpectedCertPolicy.fromEnvironment { "refuse" },
        )
        assertEquals(
            MissingExpectedCertPolicy.REFUSE,
            MissingExpectedCertPolicy.fromEnvironment { "FAIL-CLOSED" },
        )
        assertEquals(
            MissingExpectedCertPolicy.ALLOW,
            MissingExpectedCertPolicy.fromEnvironment { "surprise" },
        )
    }

    @Test
    fun `guard hashes observer bytes and refuses a substituted cert`() {
        val trustedBytes = byteArrayOf(1, 2, 3, 4)
        val trustedFp = SigningCertSha256.fromCertificateBytes(trustedBytes).canonical
        val identity = NodeSigningIdentity(
            packageName = "ai.example.node",
            expectedCerts = listOf(SigningCertSha256.parseOrThrow(trustedFp)),
            memberId = "example",
            integrationClass = FleetIntegrationClass.BOUND_COMPANION,
            notes = "fixture",
        )
        val verifier = NodeSignatureVerifier(identities = listOf(identity))
        val match = NodeSignatureGuard(
            observer = SigningCertObserver { listOf(trustedBytes) },
            verifier = verifier,
        ).check("ai.example.node")
        assertIs<NodeSignatureDecision.Allowed>(match)

        val mismatch = NodeSignatureGuard(
            observer = SigningCertObserver { listOf(byteArrayOf(9, 9, 9)) },
            verifier = verifier,
        ).check("ai.example.node")
        assertEquals(NodeSignatureRefusal.CERT_MISMATCH, (mismatch as NodeSignatureDecision.Refused).kind)

        val missing = NodeSignatureGuard(
            observer = SigningCertObserver { emptyList() },
            verifier = verifier,
        ).check("ai.example.node")
        assertEquals(NodeSignatureRefusal.OBSERVED_CERT_MISSING, (missing as NodeSignatureDecision.Refused).kind)
    }

    @Test
    fun `fromCertificateBytes is SHA-256 of the raw cert bytes`() {
        val bytes = "fixture-cert".toByteArray()
        val expected = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString(":") { "%02X".format(it) }
        assertEquals(expected, SigningCertSha256.fromCertificateBytes(bytes).canonical)
    }
}
