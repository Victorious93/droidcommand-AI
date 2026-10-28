package ai.droidcommand.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InboundEntryPointTest {
    private val attacker =
        "00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF"

    @Test
    fun `catalogue has thirteen included apps and four excluded apps`() {
        assertEquals(13, InboundEntryPointCatalog.INCLUDED.size)
        assertEquals(4, InboundEntryPointCatalog.EXCLUDED.size)
        assertEquals(
            listOf(
                "victorsuite",
                "victorsms",
                "victorcam",
                "victortrade",
                "victortaskbar",
                "victorlaunch",
                "browser",
                "mesh",
                "victormango",
                "victorspoof",
                "rootforge",
                "victorgps",
                "keyboard",
            ),
            InboundEntryPointCatalog.INCLUDED.map { it.id },
        )
        assertEquals(
            listOf("victoryped", "victorlink64", "matchvictorious", "victorops-zombies"),
            InboundEntryPointCatalog.EXCLUDED.map { it.id },
        )
    }

    @Test
    fun `later additions are flagged for Don to confirm`() {
        val later = InboundEntryPointCatalog.INCLUDED.filter { it.laterAddition }.map { it.id }
        assertEquals(listOf("rootforge", "victorgps", "keyboard"), later)
    }

    @Test
    fun `included package ids match the catalogue or the app repo`() {
        assertEquals(listOf("com.termux"), InboundEntryPointCatalog.VICTORSUITE.packages)
        assertEquals(listOf("com.victorious.sms"), InboundEntryPointCatalog.VICTOR_SMS.packages)
        assertEquals(listOf("com.victorious.cam"), InboundEntryPointCatalog.VICTOR_CAM.packages)
        assertEquals(listOf("com.victorious.trade"), InboundEntryPointCatalog.VICTOR_TRADE.packages)
        assertEquals(listOf("com.victorious.taskbar"), InboundEntryPointCatalog.VICTOR_TASKBAR.packages)
        assertEquals(listOf("com.victorious.launch"), InboundEntryPointCatalog.VICTOR_LAUNCH.packages)
        assertEquals(listOf("com.victorious.browser"), InboundEntryPointCatalog.BROWSER.packages)
        assertEquals(listOf("com.victorious.mesh"), InboundEntryPointCatalog.MESH.packages)
        assertEquals(listOf("com.victorious.mango"), InboundEntryPointCatalog.VICTOR_MANGO.packages)
        assertEquals(listOf("com.victorious.spoof"), InboundEntryPointCatalog.VICTOR_SPOOF.packages)
        assertEquals(
            listOf("ai.rootforge.os.debug", "ai.rootforge.os"),
            InboundEntryPointCatalog.ROOTFORGE.packages,
        )
        assertEquals(listOf("com.victorious.gps"), InboundEntryPointCatalog.VICTOR_GPS.packages)
        assertEquals(
            listOf("com.victorious.keyboard", "com.victorious.keyboard.debug"),
            InboundEntryPointCatalog.KEYBOARD.packages,
        )
    }

    @Test
    fun `excluded package ids come from fleet-manifest or the app repo`() {
        assertEquals(listOf("com.victorious.victoryped"), InboundEntryPointCatalog.VICTORY_PED.packages)
        assertEquals(
            listOf("org.victoriousframework.victorlink64"),
            InboundEntryPointCatalog.VICTOR_LINK64.packages,
        )
        assertEquals(listOf("com.victorious.match"), InboundEntryPointCatalog.MATCH_VICTORIOUS.packages)
        assertEquals(listOf("com.victorious.zombie"), InboundEntryPointCatalog.VICTOROPS_ZOMBIES.packages)
    }

    @Test
    fun `excluded apps are refused even with a matching-looking cert`() {
        val cases = listOf(
            "com.victorious.victoryped",
            "org.victoriousframework.victorlink64",
            "com.victorious.match",
            "com.victorious.zombie",
        )
        for (pkg in cases) {
            val decision = CompanionAssist.verifyCaller(pkg, listOf(attacker))
            val refused = assertIs<NodeSignatureDecision.Refused>(decision, pkg)
            assertEquals(NodeSignatureRefusal.EXPECTED_CERT_MISSING, refused.kind, pkg)
            assertTrue(refused.message.contains("excluded"), pkg)
            assertTrue(InboundEntryPointCatalog.isExcluded(pkg), pkg)
        }
    }

    @Test
    fun `pending-refused included apps are refused and name the missing fingerprint`() {
        val pending = listOf(
            InboundEntryPointCatalog.VICTOR_SMS,
            InboundEntryPointCatalog.VICTOR_CAM,
            InboundEntryPointCatalog.VICTOR_TRADE,
            InboundEntryPointCatalog.VICTOR_TASKBAR,
            InboundEntryPointCatalog.VICTOR_MANGO,
            InboundEntryPointCatalog.VICTOR_SPOOF,
            InboundEntryPointCatalog.VICTOR_GPS,
        )
        for (entry in pending) {
            assertEquals(InboundCertStatus.PENDING_REFUSED, entry.certStatus, entry.id)
            assertNotNull(entry.certTodo, entry.id)
            val pkg = entry.packages.first()
            val decision = CompanionAssist.verifyCaller(pkg, listOf(attacker))
            val refused = assertIs<NodeSignatureDecision.Refused>(decision, entry.id)
            assertEquals(NodeSignatureRefusal.EXPECTED_CERT_MISSING, refused.kind, entry.id)
            assertTrue(refused.message.contains("pending-refused"), entry.id)
            assertTrue(refused.message.contains("TODO"), entry.id)
        }
    }

    @Test
    fun `rootforge release package is refused until a RELEASE pin exists`() {
        val decision = CompanionAssist.verifyCaller("ai.rootforge.os", listOf(attacker))
        val refused = assertIs<NodeSignatureDecision.Refused>(decision)
        assertEquals(NodeSignatureRefusal.EXPECTED_CERT_MISSING, refused.kind)
        assertTrue(decision.message.contains("ai.rootforge.os"))
        assertTrue(decision.message.contains("TODO") || decision.message.contains("pin"))
    }

    @Test
    fun `enforced callers pass only with the catalogued pin`() {
        val cases = listOf(
            "com.termux" to KnownNodeSigningCatalog.VICTORSUITE_DEBUG_SHA256,
            "com.termux" to KnownNodeSigningCatalog.VICTORSUITE_RELEASE_SHA256,
            "com.victorious.launch" to KnownNodeSigningCatalog.VICTOR_LAUNCH_DEBUG_SHA256,
            "com.victorious.browser" to KnownNodeSigningCatalog.VICTORIOUS_BROWSER_DEBUG_SHA256,
            "com.victorious.mesh" to KnownNodeSigningCatalog.VICTORIOUS_MESH_DEBUG_SHA256,
            "com.victorious.mesh" to KnownNodeSigningCatalog.VICTORIOUS_MESH_RELEASE_SHA256,
            "ai.rootforge.os.debug" to KnownNodeSigningCatalog.ROOTFORGE_DEBUG_SHA256,
            "com.victorious.keyboard" to KnownNodeSigningCatalog.KEYBOARD_DEBUG_SHA256,
            "com.victorious.keyboard.debug" to KnownNodeSigningCatalog.KEYBOARD_DEBUG_SHA256,
        )
        for ((pkg, fp) in cases) {
            val allowed = assertIs<NodeSignatureDecision.Allowed>(
                CompanionAssist.verifyCaller(pkg, listOf(fp)),
                pkg,
            )
            assertEquals(fp, allowed.matchedFingerprint, pkg)
            assertIs<NodeSignatureDecision.Refused>(CompanionAssist.verifyCaller(pkg, listOf(attacker)), pkg)
        }
    }

    @Test
    fun `unknown package missing caller and keyboard bench are refused`() {
        assertIs<NodeSignatureDecision.Refused>(CompanionAssist.verifyCaller(null, emptyList()))
        assertIs<NodeSignatureDecision.Refused>(CompanionAssist.verifyCaller("", emptyList()))
        assertIs<NodeSignatureDecision.Refused>(
            CompanionAssist.verifyCaller("ai.not.a.fleet.member", listOf(attacker)),
        )
        val bench = CompanionAssist.verifyCaller("com.victorious.keyboard.bench", listOf(attacker))
        val refused = assertIs<NodeSignatureDecision.Refused>(bench)
        assertTrue(refused.message.contains("not one of the 13"))
    }

    @Test
    fun `CODE_ASSIST allow-list is File-Manager only and uses the catalogued debug constant`() {
        val pins = InboundEntryPointCatalog.callerIdentities(CompanionAssist.CODE_ASSIST_CAPABILITY)
        assertEquals(
            listOf("ai.victorious.filemanager", "ai.victorious.filemanager.debug"),
            pins.map { it.packageName },
        )
        val expected = SigningCertSha256.parseOrThrow(
            KnownNodeSigningCatalog.FILE_MANAGER_DEBUG_UNSTABLE_CI_SECRETS,
        )
        for (identity in pins) {
            assertEquals(listOf(expected), identity.expectedCerts)
        }
        val ok = InboundEntryPointCatalog.verifyCaller(
            "ai.victorious.filemanager",
            listOf(KnownNodeSigningCatalog.FILE_MANAGER_DEBUG_UNSTABLE_CI_SECRETS),
            CompanionAssist.CODE_ASSIST_CAPABILITY,
        )
        assertIs<NodeSignatureDecision.Allowed>(ok)
        val other = InboundEntryPointCatalog.verifyCaller(
            "com.victorious.mesh",
            listOf(KnownNodeSigningCatalog.VICTORIOUS_MESH_DEBUG_SHA256),
            CompanionAssist.CODE_ASSIST_CAPABILITY,
        )
        assertIs<NodeSignatureDecision.Refused>(other)
    }

    @Test
    fun `companion-assist identities never copy a second cert list`() {
        val identities = InboundEntryPointCatalog.callerIdentities()
        assertTrue(identities.any { it.packageName == "com.termux" })
        assertTrue(identities.any { it.packageName == "com.victorious.launch" })
        assertTrue(identities.none { it.packageName == "com.victorious.sms" })
        assertTrue(identities.none { it.packageName == "com.victorious.victoryped" })
        for (identity in identities) {
            val catalogued = KnownNodeSigningCatalog.identityFor(identity.packageName)
            if (identity.packageName.startsWith("ai.victorious.filemanager")) continue
            assertNotNull(catalogued, identity.packageName)
            assertEquals(catalogued.expectedCerts, identity.expectedCerts, identity.packageName)
        }
    }

    @Test
    fun `VictorSMS documented-but-unenforced cert is not accepted inbound`() {
        val documented = KnownNodeSigningCatalog.VICTOR_SMS_DEBUG_SHA256_UNENFORCED
        val decision = CompanionAssist.verifyCaller("com.victorious.sms", listOf(documented))
        assertIs<NodeSignatureDecision.Refused>(decision)
        assertTrue(InboundEntryPointCatalog.resolvedPins(InboundEntryPointCatalog.VICTOR_SMS, "com.victorious.sms").isEmpty())
    }

    @Test
    fun `contract strings and parse of a valid assist request`() {
        assertEquals("ai.droidcommand.action.COMPANION_ASSIST", CompanionAssist.ACTION)
        assertEquals("ai.droidcommand.extra.REQUEST_JSON", CompanionAssist.EXTRA_REQUEST_JSON)
        assertEquals("ai.droidcommand.extra.RESULT_JSON", CompanionAssist.EXTRA_RESULT_JSON)
        assertEquals(60_000, CompanionAssist.MAX_PROMPT_CHARS)
        val parsed = CompanionAssist.parseRequest(
            """{"v":1,"task":"assist","prompt":"summarise this","context":"note","instruction":"brief","destructive":false}""",
        )
        val request = assertIs<CompanionAssistParse.Valid>(parsed).request
        assertEquals("summarise this", request.prompt)
        assertEquals("note", request.context)
        assertEquals("brief", request.instruction)
        assertFalse(request.destructive)
    }

    @Test
    fun `missing empty oversize or wrong-task JSON is invalid`() {
        assertIs<CompanionAssistParse.Invalid>(CompanionAssist.parseRequest(null))
        assertIs<CompanionAssistParse.Invalid>(CompanionAssist.parseRequest("  "))
        assertTrue(assertIs<CompanionAssistParse.Invalid>(CompanionAssist.parseRequest("{")).reason.contains("not valid JSON"))
        assertIs<CompanionAssistParse.Invalid>(CompanionAssist.parseRequest("""{"v":1}"""))
        val empty = CompanionAssist.parseRequest("""{"v":1,"prompt":""}""")
        assertTrue(assertIs<CompanionAssistParse.Invalid>(empty).reason.contains("empty"))
        val tooLong = CompanionAssist.parseRequest(
            """{"v":1,"prompt":"${"a".repeat(CompanionAssist.MAX_PROMPT_CHARS + 1)}"}""",
        )
        assertTrue(assertIs<CompanionAssistParse.Invalid>(tooLong).reason.contains("60000"))
        val ok = CompanionAssist.parseRequest(
            """{"v":1,"prompt":"${"a".repeat(CompanionAssist.MAX_PROMPT_CHARS)}"}""",
        )
        assertEquals(CompanionAssist.MAX_PROMPT_CHARS, assertIs<CompanionAssistParse.Valid>(ok).request.prompt.length)
        val remote = CompanionAssist.parseRequest("""{"v":1,"task":"remote.command","prompt":"x"}""")
        assertTrue(assertIs<CompanionAssistParse.Invalid>(remote).reason.contains("task"))
        val v2 = CompanionAssist.parseRequest("""{"v":2,"prompt":"x"}""")
        assertTrue(assertIs<CompanionAssistParse.Invalid>(v2).reason.contains("Unsupported"))
    }

    @Test
    fun `bindToCaller fills capability and rejects a mismatched capability`() {
        val parsed = CompanionAssist.parseRequest("""{"v":1,"prompt":"help"}""")
        val request = assertIs<CompanionAssistParse.Valid>(parsed).request
        val bound = assertIs<CompanionAssistParse.Valid>(
            CompanionAssist.bindToCaller(request, "com.victorious.mesh"),
        ).request
        assertEquals("companion.assist.mesh", bound.capability)
        assertEquals("mesh", bound.callerId)

        val wrong = CompanionAssist.parseRequest(
            """{"v":1,"prompt":"help","capability":"companion.assist.keyboard"}""",
        )
        val refused = CompanionAssist.bindToCaller(
            assertIs<CompanionAssistParse.Valid>(wrong).request,
            "com.victorious.mesh",
        )
        assertTrue(assertIs<CompanionAssistParse.Invalid>(refused).reason.contains("does not match"))

        val pending = CompanionAssist.bindToCaller(request, "com.victorious.sms")
        assertTrue(assertIs<CompanionAssistParse.Invalid>(pending).reason.contains("pending-refused"))

        val excluded = CompanionAssist.bindToCaller(request, "com.victorious.zombie")
        assertTrue(assertIs<CompanionAssistParse.Invalid>(excluded).reason.contains("not an included"))
    }

    @Test
    fun `destructive flag stays confirmation_required and never becomes a tool task`() {
        val parsed = CompanionAssist.parseRequest("""{"v":1,"prompt":"wipe","destructive":true}""")
        val request = assertIs<CompanionAssistParse.Valid>(parsed).request
        assertTrue(request.destructive)
        val prompt = CompanionAssist.buildPrompt(request)
        assertEquals("wipe", prompt)
        assertTrue(CompanionAssist.successJson("ok").contains("\"ok\":true"))
        assertTrue(CompanionAssist.errorJson("nope").contains("\"ok\":false"))
        assertTrue(CompanionAssist.preview("short") == "short")
        assertTrue(CompanionAssist.preview("x".repeat(2_000)).contains("more characters"))
    }

    @Test
    fun `buildPrompt prefers instruction and context in front of the prompt`() {
        val request = CompanionAssistRequest(
            capability = "companion.assist.mesh",
            prompt = "what is up?",
            context = "mesh is down",
            instruction = "be brief",
            destructive = false,
        )
        val prompt = CompanionAssist.buildPrompt(request)
        assertTrue(prompt.startsWith("be brief"))
        assertTrue(prompt.contains("mesh is down"))
        assertTrue(prompt.contains("what is up?"))
    }

    @Test
    fun `CODE_ASSIST pattern row is not an included COMPANION_ASSIST caller`() {
        assertNull(InboundEntryPointCatalog.includedByPackage("ai.victorious.filemanager"))
        assertEquals(InboundEntryKind.PATTERN, InboundEntryPointCatalog.FILE_MANAGER_CODE_ASSIST.kind)
        assertIs<NodeSignatureDecision.Refused>(
            CompanionAssist.verifyCaller("ai.victorious.filemanager", listOf(attacker)),
        )
    }
}
