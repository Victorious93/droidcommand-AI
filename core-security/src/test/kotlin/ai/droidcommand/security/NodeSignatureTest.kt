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
    private val meshRelease =
        "76:E9:15:83:31:40:DA:96:69:D3:CC:5D:54:F6:66:A9:01:51:51:FC:F6:4C:59:B6:3D:DA:6E:D2:8F:83:A4:0D"
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
        assertEquals(listOf(meshDebug, meshRelease), refused.expected)
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
        val decision = NodeSignatureVerifier().verify("ai.victorious.victorfiles", listOf(attacker))
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
        val decision = verifier.verify("ai.victorious.victorfiles", listOf(attacker))
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
            "com.victorious.mesh" to KnownNodeSigningCatalog.VICTORIOUS_MESH_RELEASE_SHA256,
            "com.victorious.browser" to KnownNodeSigningCatalog.VICTORIOUS_BROWSER_DEBUG_SHA256,
            "com.victorious.flash.android" to KnownNodeSigningCatalog.VICTORIOUS_FLASH_DEBUG_SHA256,
            "com.victorious.launch" to KnownNodeSigningCatalog.VICTOR_LAUNCH_DEBUG_SHA256,
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
            "com.victorious.flash.android",
            "com.victorious.launch",
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
            listOf("ai.victorious.victorfiles", "ai.victorious.victorfiles.debug"),
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
        assertEquals(listOf("com.identityshield.companion"), KnownFleet.KYC_AUDITLAB.androidPackages)
        assertEquals(listOf("com.victorious.flash.android"), KnownFleet.VICTORIOUS_FLASH.androidPackages)
        assertEquals(listOf("com.victorious.launch"), KnownFleet.VICTOR_LAUNCH.androidPackages)
        assertTrue(KnownFleet.FILE_MANAGER.expectedSigningCertSha256.isEmpty())
        assertEquals(
            KnownNodeSigningCatalog.VICTOR_LAUNCH_DEBUG_SHA256,
            KnownFleet.VICTOR_LAUNCH.signingCertLabel(),
        )
        assertEquals("[Unknown]", KnownFleet.FILE_MANAGER.signingCertLabel())
        assertEquals("[Unknown]", KnownFleet.KYC_AUDITLAB.signingCertLabel())
        assertEquals(
            KnownNodeSigningCatalog.VICTORIOUS_FLASH_DEBUG_SHA256,
            KnownFleet.VICTORIOUS_FLASH.signingCertLabel(),
        )
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
            "com.victorious.mesh",
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
    fun `VictorSMS debug cert is documented but not enforced`() {
        val documented = SigningCertSha256.parseOrThrow(
            KnownNodeSigningCatalog.VICTOR_SMS_DEBUG_SHA256_UNENFORCED,
        )
        val identity = KnownNodeSigningCatalog.identityFor("com.victorious.sms")
        assertTrue(identity?.expectedCerts.isNullOrEmpty())
        assertTrue(KnownFleet.VICTOR_SMS.expectedSigningCertSha256.isEmpty())
        assertIs<NodeSignatureDecision.Allowed>(
            NodeSignatureVerifier().verify(
                "com.victorious.sms",
                listOf(documented.canonical),
            ),
        )
        assertTrue(
            KnownNodeSigningCatalog.all().none { candidate ->
                documented in candidate.expectedCerts
            },
        )
        assertEquals(
            "BA:81:F5:C7:6E:2C:59:04:E8:A0:E5:0C:80:67:A2:A1:6C:23:14:86:2F:13:DA:7F:49:67:94:8A:53:E7:D1:7A",
            KnownNodeSigningCatalog.VICTOR_SMS_DEBUG_SHA256_UNENFORCED,
        )
    }

    @Test
    fun `VictorLaunch debug is enforced and the rotated cert is pinned`() {
        val identity = KnownNodeSigningCatalog.VICTOR_LAUNCH_DEBUG
        assertEquals("com.victorious.launch", identity.packageName)
        assertEquals(FleetIntegrationClass.EXTERNAL_NODE, identity.integrationClass)
        assertEquals("victorlaunch", identity.memberId)
        assertEquals(
            listOf(KnownNodeSigningCatalog.VICTOR_LAUNCH_DEBUG_SHA256),
            identity.expectedCerts.map { it.canonical },
        )
        assertEquals(
            "B9:9A:AF:E2:C1:41:8B:5D:64:AA:0D:4B:AC:61:FC:F7:A7:BA:81:44:35:E0:EF:89:E8:AA:55:BE:56:4E:4A:69",
            KnownNodeSigningCatalog.VICTOR_LAUNCH_DEBUG_SHA256,
        )
        assertEquals(identity, KnownNodeSigningCatalog.identityFor("com.victorious.launch"))
        assertTrue(identity.notes.contains("Enforceable"))
        assertTrue(identity.notes.contains("RELEASE is TBD"))
        assertEquals(listOf("com.victorious.launch"), KnownFleet.VICTOR_LAUNCH.androidPackages)
        assertEquals(
            listOf(KnownNodeSigningCatalog.VICTOR_LAUNCH_DEBUG_SHA256),
            KnownFleet.VICTOR_LAUNCH.expectedSigningCertSha256,
        )
        assertEquals(
            KnownNodeSigningCatalog.VICTOR_LAUNCH_DEBUG_SHA256,
            KnownFleet.VICTOR_LAUNCH.signingCertLabel(),
        )
        assertEquals(13, KnownFleet.VICTOR_LAUNCH.wiringOrder)
        assertEquals(FleetHonestyStatus.PLANNED, KnownFleet.VICTOR_LAUNCH.status)
        val allowed = assertIs<NodeSignatureDecision.Allowed>(
            NodeSignatureVerifier().verify(
                "com.victorious.launch",
                listOf(KnownNodeSigningCatalog.VICTOR_LAUNCH_DEBUG_SHA256),
            ),
        )
        assertEquals(KnownNodeSigningCatalog.VICTOR_LAUNCH_DEBUG_SHA256, allowed.matchedFingerprint)
        val revoked = SigningCertSha256.parseOrThrow(
            "1B:06:A8:9E:7F:F5:BA:21:C9:A4:F8:F1:F0:4F:95:E5:62:78:A1:22:18:AD:37:33:28:CE:E7:83:1D:4F:BA:CA",
        )
        assertTrue(
            KnownNodeSigningCatalog.all().none { candidate ->
                revoked in candidate.expectedCerts
            },
        )
        val refusedOld = assertIs<NodeSignatureDecision.Refused>(
            NodeSignatureVerifier().verify(
                "com.victorious.launch",
                listOf(revoked.canonical),
            ),
        )
        assertEquals(NodeSignatureRefusal.CERT_MISMATCH, refusedOld.kind)
        val refused = assertIs<NodeSignatureDecision.Refused>(
            NodeSignatureVerifier().verify(
                "com.victorious.launch",
                listOf("00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF"),
            ),
        )
        assertEquals(NodeSignatureRefusal.CERT_MISMATCH, refused.kind)
    }

    @Test
    fun `KYC companion debug cert is documented but not enforced`() {
        val documented = SigningCertSha256.parseOrThrow(
            KnownNodeSigningCatalog.KYC_AUDITLAB_DEBUG_SHA256_UNENFORCED,
        )
        val identity = KnownNodeSigningCatalog.identityFor("com.identityshield.companion")
        assertTrue(identity?.expectedCerts.isNullOrEmpty())
        assertTrue(KnownFleet.KYC_AUDITLAB.expectedSigningCertSha256.isEmpty())
        assertEquals("[Unknown]", KnownFleet.KYC_AUDITLAB.signingCertLabel())
        assertIs<NodeSignatureDecision.Allowed>(
            NodeSignatureVerifier().verify(
                "com.identityshield.companion",
                listOf(documented.canonical),
            ),
        )
        assertTrue(
            KnownNodeSigningCatalog.all().none { candidate ->
                documented in candidate.expectedCerts
            },
        )
        assertEquals(
            "6F:46:77:2A:B4:C1:E0:2F:12:F3:ED:9D:7A:65:FD:CD:61:32:B2:71:D9:07:75:FF:5A:15:9A:68:55:C9:2D:4D",
            KnownNodeSigningCatalog.KYC_AUDITLAB_DEBUG_SHA256_UNENFORCED,
        )
        val pending = SigningCertSha256.parseOrThrow(
            KnownNodeSigningCatalog.KYC_AUDITLAB_PENDING_STABLE_DEBUG_SHA256_UNENFORCED,
        )
        assertIs<NodeSignatureDecision.Allowed>(
            NodeSignatureVerifier().verify(
                "com.identityshield.companion",
                listOf(pending.canonical),
            ),
        )
        assertTrue(
            KnownNodeSigningCatalog.all().none { candidate ->
                pending in candidate.expectedCerts
            },
        )
        assertEquals(
            "22:4F:C1:64:D6:8A:28:88:B9:FE:8F:10:C7:B6:B5:E5:78:67:2B:D2:F8:97:05:06:68:4B:23:A9:43:FA:44:1D",
            KnownNodeSigningCatalog.KYC_AUDITLAB_PENDING_STABLE_DEBUG_SHA256_UNENFORCED,
        )
    }

    @Test
    fun `File-Manager unstable debug cert is documented but not enforced`() {
        val unstable = SigningCertSha256.parseOrThrow(
            KnownNodeSigningCatalog.FILE_MANAGER_DEBUG_UNSTABLE_CI_SECRETS,
        )
        for (pkg in listOf("ai.victorious.victorfiles", "ai.victorious.victorfiles.debug")) {
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
    fun `Victorious Flash debug is enforced and release stays TBD`() {
        val identity = KnownNodeSigningCatalog.VICTORIOUS_FLASH_DEBUG
        assertEquals("com.victorious.flash.android", identity.packageName)
        assertEquals(FleetIntegrationClass.EXTERNAL_NODE, identity.integrationClass)
        assertEquals("victorious-flash", identity.memberId)
        assertEquals(
            listOf(KnownNodeSigningCatalog.VICTORIOUS_FLASH_DEBUG_SHA256),
            identity.expectedCerts.map { it.canonical },
        )
        assertEquals(
            "2C:52:0F:7A:A4:BE:72:6D:EB:F9:18:3D:04:3D:56:C5:58:E5:67:F7:77:BD:10:BE:3C:98:86:38:1C:6A:F6:ED",
            KnownNodeSigningCatalog.VICTORIOUS_FLASH_DEBUG_SHA256,
        )
        assertEquals(identity, KnownNodeSigningCatalog.identityFor("com.victorious.flash.android"))
        assertTrue(identity.notes.contains("Enforceable"))
        assertTrue(identity.notes.contains("RELEASE is TBD"))
        assertTrue(identity.notes.contains("gadget.expose is DESTRUCTIVE"))
        assertEquals(listOf("com.victorious.flash.android"), KnownFleet.VICTORIOUS_FLASH.androidPackages)
        assertEquals(
            listOf(KnownNodeSigningCatalog.VICTORIOUS_FLASH_DEBUG_SHA256),
            KnownFleet.VICTORIOUS_FLASH.expectedSigningCertSha256,
        )
        assertEquals(12, KnownFleet.VICTORIOUS_FLASH.wiringOrder)
        assertEquals(FleetHonestyStatus.PLANNED, KnownFleet.VICTORIOUS_FLASH.status)
        val allowed = assertIs<NodeSignatureDecision.Allowed>(
            NodeSignatureVerifier().verify(
                "com.victorious.flash.android",
                listOf(KnownNodeSigningCatalog.VICTORIOUS_FLASH_DEBUG_SHA256),
            ),
        )
        assertEquals(KnownNodeSigningCatalog.VICTORIOUS_FLASH_DEBUG_SHA256, allowed.matchedFingerprint)
        val refused = assertIs<NodeSignatureDecision.Refused>(
            NodeSignatureVerifier().verify(
                "com.victorious.flash.android",
                listOf("00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF"),
            ),
        )
        assertEquals(NodeSignatureRefusal.CERT_MISMATCH, refused.kind)
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
        assertEquals(listOf("com.victorious.browser"), KnownFleet.BROWSER.androidPackages)
        assertEquals(9, KnownFleet.BROWSER.wiringOrder)
    }

    @Test
    fun `Victorious Mesh debug and own release fingerprints are the documented values`() {
        val identity = KnownNodeSigningCatalog.VICTORIOUS_MESH_DEBUG
        assertEquals("com.victorious.mesh", identity.packageName)
        assertEquals(FleetIntegrationClass.EXTERNAL_NODE, identity.integrationClass)
        assertEquals(
            listOf(
                KnownNodeSigningCatalog.VICTORIOUS_MESH_DEBUG_SHA256,
                KnownNodeSigningCatalog.VICTORIOUS_MESH_RELEASE_SHA256,
            ),
            identity.expectedCerts.map { it.canonical },
        )
        assertEquals(listOf(meshDebug, meshRelease), identity.expectedCerts.map { it.canonical })
        assertEquals(identity, KnownNodeSigningCatalog.identityFor("com.victorious.mesh"))
        assertTrue(identity.notes.contains("NOT shared fleet"))
        assertEquals(
            listOf(
                KnownNodeSigningCatalog.VICTORIOUS_MESH_DEBUG_SHA256,
                KnownNodeSigningCatalog.VICTORIOUS_MESH_RELEASE_SHA256,
            ),
            KnownFleet.MESH.expectedSigningCertSha256,
        )
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
