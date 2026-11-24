package ai.droidcommand.communications

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShareFormTest {
    private fun build(f: ShareForm) = ShareForms.build(f, 1_000L) { "id1" }
    private val base = ShareForm("conv-A", path = "/sdcard/DCIM/Cats", ops = setOf(FileOp.BROWSE, FileOp.SEND))

    @Test fun `a valid form builds a read-only, approval-required, expiring grant`() {
        val g = build(base).getOrThrow()
        assertTrue(g.readOnly && g.requireApproval)
        assertEquals(1_000L + 24 * 3_600_000L, g.expiresAtMs)
        assertEquals(ShareScope.Folder("/sdcard/DCIM/Cats", false), g.scopes.single())
        assertNull(g.allowedRecipients)
        assertNull(g.subject.botId)
    }

    @Test fun `read-only is derived from the operations so it can never contradict them`() {
        assertFalse(build(base.copy(ops = setOf(FileOp.MODIFY))).getOrThrow().readOnly)
        assertTrue(build(base.copy(ops = setOf(FileOp.READ, FileOp.SEND))).getOrThrow().readOnly)
    }

    @Test fun `every kind maps to the right scope`() {
        assertIs<ShareScope.File>(build(base.copy(kind = ShareKind.FILE, path = "/sdcard/a.jpg")).getOrThrow().scopes.single())
        assertEquals(true, (build(base.copy(kind = ShareKind.FOLDER_WITH_SUBFOLDERS)).getOrThrow().scopes.single() as ShareScope.Folder).subfolders)
    }

    @Test fun `refuses missing choices and unsafe input with a reason`() {
        assertTrue(build(base.copy(conversation = " ")).isFailure)
        assertTrue(build(base.copy(ops = emptySet())).isFailure)
        for (p in listOf("", "relative/path", "/a/../b", "/a/%2e%2e/b", "/")) assertTrue(build(base.copy(path = p)).isFailure, p)
        assertTrue(build(base.copy(ttlHours = 0)).isFailure)
        assertTrue(build(base.copy(ttlHours = ShareForms.MAX_TTL_HOURS + 1)).isFailure)
        assertTrue(build(base.copy(recipients = (1..21).joinToString(",") { "55500$it" })).isFailure)
    }

    @Test fun `no expiry, bot id and recipient parsing`() {
        val g = build(base.copy(ttlHours = null, botId = " bot1 ", recipients = "+15551230001, +15551230002\n+15551230001")).getOrThrow()
        assertNull(g.expiresAtMs)
        assertEquals("bot1", g.subject.botId)
        assertEquals(setOf("+15551230001", "+15551230002"), g.allowedRecipients)
    }

    @Test fun `a built grant is accepted by the engine and then works`() {
        val e = FileShareEngine(clock = { 2_000L })
        e.grant(build(base.copy(requireApproval = false, recipients = "+15551230001")).getOrThrow()).getOrThrow()
        val d = e.decide(ShareRequest(ShareSubject("conv-A"), FileOp.SEND, "/sdcard/DCIM/Cats/a.jpg", "+15551230001"))
        assertIs<ShareDecision.Allow>(d)
    }
}
