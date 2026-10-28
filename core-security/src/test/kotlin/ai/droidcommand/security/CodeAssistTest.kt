package ai.droidcommand.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodeAssistTest {
    private val debugPin = KnownNodeSigningCatalog.FILE_MANAGER_DEBUG_SHA256
    private val attacker =
        "00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF"

    @Test
    fun `contract strings match PHASE17 field names`() {
        assertEquals("ai.droidcommand.action.CODE_ASSIST", CodeAssist.ACTION)
        assertEquals("ai.droidcommand.extra.REQUEST_JSON", CodeAssist.EXTRA_REQUEST_JSON)
        assertEquals("ai.droidcommand.extra.RESULT_JSON", CodeAssist.EXTRA_RESULT_JSON)
        assertEquals(60_000, CodeAssist.MAX_CODE_CHARS)
        assertEquals("explain", CodeAssist.TASK_EXPLAIN)
        assertEquals("suggest_edit", CodeAssist.TASK_SUGGEST_EDIT)
    }

    @Test
    fun `parses an explain request with every PHASE17 field`() {
        val parsed = CodeAssist.parseRequest(
            """{"v":1,"task":"explain","fileName":"Foo.smali","language":"SMALI",
                "code":".method foo()V","instruction":"be brief","truncated":false,
                "prompt":"ready-made prompt incl. code"}""",
        )
        val request = assertIs<CodeAssistParse.Valid>(parsed).request
        assertEquals(CodeAssistTask.EXPLAIN, request.task)
        assertEquals("Foo.smali", request.fileName)
        assertEquals("SMALI", request.language)
        assertEquals(".method foo()V", request.code)
        assertEquals("be brief", request.instruction)
        assertEquals("ready-made prompt incl. code", request.prompt)
        assertFalse(request.truncated)
    }

    @Test
    fun `parses suggest_edit and ignores unknown fields`() {
        val parsed = CodeAssist.parseRequest(
            """{"v":1,"task":"suggest_edit","fileName":"Main.kt","language":"Kotlin",
                "code":"fun x() = 1","extra":true}""",
        )
        val request = assertIs<CodeAssistParse.Valid>(parsed).request
        assertEquals(CodeAssistTask.SUGGEST_EDIT, request.task)
        assertEquals("fun x() = 1", request.code)
        assertEquals("", request.prompt)
        assertFalse(request.truncated)
    }

    @Test
    fun `missing JSON is invalid`() {
        assertIs<CodeAssistParse.Invalid>(CodeAssist.parseRequest(null))
        assertIs<CodeAssistParse.Invalid>(CodeAssist.parseRequest("  "))
        assertTrue(assertIs<CodeAssistParse.Invalid>(CodeAssist.parseRequest("{")).reason.contains("not valid JSON"))
    }

    @Test
    fun `v greater than 1 is refused`() {
        val parsed = CodeAssist.parseRequest("""{"v":2,"task":"explain","code":"x"}""")
        assertTrue(assertIs<CodeAssistParse.Invalid>(parsed).reason.contains("Unsupported"))
    }

    @Test
    fun `unknown task is refused`() {
        val parsed = CodeAssist.parseRequest("""{"v":1,"task":"rewrite","code":"x"}""")
        assertTrue(assertIs<CodeAssistParse.Invalid>(parsed).reason.contains("task"))
    }

    @Test
    fun `missing or empty code is refused`() {
        assertIs<CodeAssistParse.Invalid>(CodeAssist.parseRequest("""{"v":1,"task":"explain"}"""))
        val empty = CodeAssist.parseRequest("""{"v":1,"task":"explain","code":""}""")
        assertTrue(assertIs<CodeAssistParse.Invalid>(empty).reason.contains("empty"))
    }

    @Test
    fun `code at the 60000-char cap is accepted and one extra char is refused`() {
        val ok = "a".repeat(CodeAssist.MAX_CODE_CHARS)
        val parsed = CodeAssist.parseRequest("""{"v":1,"task":"explain","code":"$ok"}""")
        assertEquals(CodeAssist.MAX_CODE_CHARS, assertIs<CodeAssistParse.Valid>(parsed).request.code.length)

        val tooLong = "a".repeat(CodeAssist.MAX_CODE_CHARS + 1)
        val refused = CodeAssist.parseRequest("""{"v":1,"task":"explain","code":"$tooLong"}""")
        assertTrue(assertIs<CodeAssistParse.Invalid>(refused).reason.contains("60000"))
    }

    @Test
    fun `ready-made prompt wins over a locally built one`() {
        val request = CodeAssistRequest(
            task = CodeAssistTask.EXPLAIN,
            fileName = "Foo.kt",
            language = "Kotlin",
            code = "val x = 1",
            instruction = "ignored",
            prompt = "Use this exact prompt",
            truncated = false,
        )
        assertEquals("Use this exact prompt", CodeAssist.buildPrompt(request))
    }

    @Test
    fun `built explain and suggest_edit prompts include the code`() {
        val explain = CodeAssistRequest(
            task = CodeAssistTask.EXPLAIN,
            fileName = "Foo.smali",
            language = "SMALI",
            code = ".method foo()V",
            instruction = "be brief",
            prompt = "",
            truncated = false,
        )
        val explainPrompt = CodeAssist.buildPrompt(explain)
        assertTrue(explainPrompt.contains("SMALI"))
        assertTrue(explainPrompt.contains("Foo.smali"))
        assertTrue(explainPrompt.contains(".method foo()V"))
        assertTrue(explainPrompt.contains("be brief"))

        val edit = explain.copy(task = CodeAssistTask.SUGGEST_EDIT, instruction = "rename foo")
        val editPrompt = CodeAssist.buildPrompt(edit)
        assertTrue(editPrompt.contains("full revised file"))
        assertTrue(editPrompt.contains("rename foo"))
        assertTrue(editPrompt.contains(".method foo()V"))
    }

    @Test
    fun `success JSON for explain omits revisedCode`() {
        val json = CodeAssist.successJson(CodeAssistTask.EXPLAIN, "It prints hello.")
        assertTrue(json.contains("\"ok\":true"))
        assertTrue(json.contains("\"v\":1"))
        assertTrue(json.contains("It prints hello."))
        assertFalse(json.contains("revisedCode"))
    }

    @Test
    fun `success JSON for suggest_edit uses the largest fenced block`() {
        val model = "Intro.\n```kotlin\nsmall\n```\n```smali\n.method big()V\nreturn-void\n.end method\n```\nDone."
        assertEquals(
            ".method big()V\nreturn-void\n.end method",
            CodeAssist.revisedCodeFrom(model),
        )
        val json = CodeAssist.successJson(CodeAssistTask.SUGGEST_EDIT, model)
        assertTrue(json.contains("\"ok\":true"))
        assertTrue(json.contains("revisedCode"))
        assertTrue(json.contains(".method big()V"))
    }

    @Test
    fun `suggest_edit without fences uses the full model text`() {
        assertEquals("fun x() = 2", CodeAssist.revisedCodeFrom("fun x() = 2"))
    }

    @Test
    fun `error JSON uses PHASE17 ok false shape and escapes quotes`() {
        val json = CodeAssist.errorJson("No API key for \"Anthropic\".")
        assertTrue(json.contains("\"ok\":false"))
        assertTrue(json.contains("\"v\":1"))
        assertTrue(json.contains("No API key"))
        assertFalse(json.contains("\"ok\":true"))
    }

    @Test
    fun `preview truncates long code`() {
        val preview = CodeAssist.preview("abcdefghij", maxChars = 4)
        assertTrue(preview.startsWith("abcd"))
        assertTrue(preview.contains("6 more characters"))
        assertEquals("short", CodeAssist.preview("short", maxChars = 40))
    }

    @Test
    fun `missing caller identity is refused`() {
        val none = assertIs<NodeSignatureDecision.Refused>(CodeAssist.verifyCaller(null, listOf(debugPin)))
        assertEquals(NodeSignatureRefusal.EXPECTED_CERT_MISSING, none.kind)
        val blank = assertIs<NodeSignatureDecision.Refused>(CodeAssist.verifyCaller("  ", listOf(debugPin)))
        assertEquals(NodeSignatureRefusal.EXPECTED_CERT_MISSING, blank.kind)
    }

    @Test
    fun `unknown caller package is refused even with the File-Manager pin`() {
        val refused = assertIs<NodeSignatureDecision.Refused>(
            CodeAssist.verifyCaller("com.evil.imposter", listOf(debugPin)),
        )
        assertEquals(NodeSignatureRefusal.EXPECTED_CERT_MISSING, refused.kind)
    }

    @Test
    fun `File-Manager debug package is allowed only with the catalogued pin`() {
        for (pkg in listOf(CodeAssist.FILE_MANAGER_PACKAGE, CodeAssist.FILE_MANAGER_DEBUG_PACKAGE)) {
            val allowed = assertIs<NodeSignatureDecision.Allowed>(
                CodeAssist.verifyCaller(pkg, listOf(debugPin)),
                pkg,
            )
            assertEquals(debugPin, allowed.matchedFingerprint)
            val mismatch = assertIs<NodeSignatureDecision.Refused>(
                CodeAssist.verifyCaller(pkg, listOf(attacker)),
                pkg,
            )
            assertEquals(NodeSignatureRefusal.CERT_MISMATCH, mismatch.kind)
            val missing = assertIs<NodeSignatureDecision.Refused>(
                CodeAssist.verifyCaller(pkg, emptyList()),
                pkg,
            )
            assertEquals(NodeSignatureRefusal.OBSERVED_CERT_MISSING, missing.kind)
        }
    }

    @Test
    fun `CODE_ASSIST pin does not change fleet outbound fail-open for File-Manager`() {
        val fleet = NodeSignatureVerifier().verify(CodeAssist.FILE_MANAGER_PACKAGE, listOf(attacker))
        assertIs<NodeSignatureDecision.Allowed>(fleet)
        assertNull(fleet.matchedFingerprint)
        assertTrue(KnownFleet.FILE_MANAGER.expectedSigningCertSha256.isEmpty())
    }

    @Test
    fun `debug pin constant is the PHASE17 File-Manager fingerprint`() {
        assertEquals(
            "8C:1F:E3:C9:E5:34:F2:71:05:C0:68:8F:45:28:D7:7E:47:11:AE:0A:79:DD:08:D1:E8:59:30:B9:E9:48:85:F2",
            KnownNodeSigningCatalog.FILE_MANAGER_DEBUG_SHA256,
        )
        assertEquals(
            KnownNodeSigningCatalog.FILE_MANAGER_DEBUG_UNSTABLE_CI_SECRETS,
            KnownNodeSigningCatalog.FILE_MANAGER_DEBUG_SHA256,
        )
        val identities = KnownNodeSigningCatalog.codeAssistCallerIdentities()
        assertEquals(
            listOf(CodeAssist.FILE_MANAGER_PACKAGE, CodeAssist.FILE_MANAGER_DEBUG_PACKAGE),
            identities.map { it.packageName },
        )
        assertTrue(identities.all { it.notes.contains("TODO") })
        assertTrue(identities.all { it.notes.contains("RELEASE") })
    }
}
