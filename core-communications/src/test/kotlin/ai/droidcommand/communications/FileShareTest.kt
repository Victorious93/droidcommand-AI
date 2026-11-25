package ai.droidcommand.communications

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileShareTest {
    private var now = 1_000_000L
    private val cats = "/storage/emulated/0/DCIM/Cats"
    private val a = ShareSubject("conv-A", "bot1", "+15551230001")
    private val b = ShareSubject("conv-B", "bot1", "+15551230002")

    private fun engine(stop: () -> Boolean = { false }) = FileShareEngine(clock = { now }, emergencyStop = stop)

    private fun grant(
        id: String = "g1", subject: ShareSubject = a, scopes: List<ShareScope> = listOf(ShareScope.Folder(cats)),
        ops: Set<FileOp> = setOf(FileOp.BROWSE, FileOp.SEND), readOnly: Boolean = true, approval: Boolean = false, exp: Long? = null,
        recipients: Set<String>? = null,
    ) = ShareGrant(id, subject, scopes, ops, readOnly, approval, exp, recipients, now)

    private fun ask(e: FileShareEngine, s: ShareSubject = a, op: FileOp = FileOp.SEND, path: String = "$cats/a.jpg", to: String? = a.contact, origin: Origin = Origin.BOT) =
        e.decide(ShareRequest(s, op, path, to, origin))

    @Test fun `default is deny`() {
        assertIs<ShareDecision.Deny>(ask(engine()))
    }

    @Test fun `grant allows only its conversation`() {
        val e = engine().also { it.grant(grant()).getOrThrow() }
        assertIs<ShareDecision.Allow>(ask(e))
        assertIs<ShareDecision.Deny>(ask(e, s = b, to = b.contact)) // no cross-conversation leakage
    }

    @Test fun `grant bound to a bot does not cover another bot in the same conversation`() {
        val e = engine().also { it.grant(grant()).getOrThrow() }
        assertIs<ShareDecision.Deny>(ask(e, s = a.copy(botId = "bot2")))
    }

    @Test fun `operations not granted are denied`() {
        val e = engine().also { it.grant(grant()).getOrThrow() }
        assertIs<ShareDecision.Deny>(ask(e, op = FileOp.READ))
        assertIs<ShareDecision.Deny>(ask(e, op = FileOp.DOWNLOAD))
    }

    @Test fun `subfolders only when granted`() {
        val e = engine().also { it.grant(grant()).getOrThrow() }
        assertIs<ShareDecision.Deny>(ask(e, path = "$cats/sub/a.jpg"))
        e.grant(grant("g2", scopes = listOf(ShareScope.Folder(cats, subfolders = true)))).getOrThrow()
        assertIs<ShareDecision.Allow>(ask(e, path = "$cats/sub/deep/a.jpg"))
    }

    @Test fun `sibling folder with the same prefix is not covered`() {
        val e = engine().also { it.grant(grant(scopes = listOf(ShareScope.Folder(cats, true)))).getOrThrow() }
        assertIs<ShareDecision.Deny>(ask(e, path = "${cats}Private/a.jpg"))
        assertIs<ShareDecision.Allow>(ask(e, op = FileOp.BROWSE, path = cats))
    }

    @Test fun `individual file and collection scopes`() {
        val e = engine()
        e.grant(grant("f", scopes = listOf(ShareScope.File("/sdcard/Docs/menu.pdf")))).getOrThrow()
        e.grant(grant("c", scopes = listOf(ShareScope.Collection("album", setOf("/sdcard/P/1.jpg", "/sdcard/P/2.jpg"))))).getOrThrow()
        assertIs<ShareDecision.Allow>(ask(e, path = "/sdcard/Docs/menu.pdf"))
        assertIs<ShareDecision.Deny>(ask(e, path = "/sdcard/Docs/other.pdf"))
        assertIs<ShareDecision.Allow>(ask(e, path = "/sdcard/P/2.jpg"))
        assertIs<ShareDecision.Deny>(ask(e, path = "/sdcard/P/3.jpg"))
    }

    @Test fun `traversal and odd paths are denied not repaired`() {
        val e = engine().also { it.grant(grant(scopes = listOf(ShareScope.Folder(cats, true)))).getOrThrow() }
        for (p in listOf("$cats/../Secret/a.jpg", "$cats/%2e%2e/a.jpg", "$cats\\a.jpg", "$cats/a.jpg\u0000.png", "Cats/a.jpg", "")) {
            assertIs<ShareDecision.Deny>(ask(e, path = p), p)
        }
        assertEquals("/a/b", SharePaths.normalize("/a//./b/"))
    }

    @Test fun `grant with an unsafe path is refused at creation`() {
        assertTrue(engine().grant(grant(scopes = listOf(ShareScope.Folder("/x/../y")))).isFailure)
    }

    @Test fun `read-only grant cannot carry writing operations`() {
        assertTrue(engine().grant(grant(ops = setOf(FileOp.MODIFY), readOnly = true)).isFailure)
        val e = engine().also { it.grant(grant(ops = setOf(FileOp.MODIFY, FileOp.READ), readOnly = false)).getOrThrow() }
        assertIs<ShareDecision.Allow>(ask(e, op = FileOp.MODIFY))
    }

    @Test fun `expiry is honoured`() {
        val e = engine().also { it.grant(grant(exp = now + 1_000)).getOrThrow() }
        assertIs<ShareDecision.Allow>(ask(e))
        now += 1_000
        assertIs<ShareDecision.Deny>(ask(e))
    }

    @Test fun `revocation is immediate and per conversation`() {
        val e = engine()
        e.grant(grant("g1")).getOrThrow()
        e.grant(grant("g2", subject = b)).getOrThrow()
        assertIs<ShareDecision.Allow>(ask(e))
        assertEquals(1, e.revokeConversation("conv-A"))
        assertIs<ShareDecision.Deny>(ask(e))
        assertIs<ShareDecision.Allow>(ask(e, s = b, to = b.contact))
        assertFalse(e.revoke("g1"))
    }

    @Test fun `approval-required grant never silently allows`() {
        val e = engine().also { it.grant(grant(approval = true)).getOrThrow() }
        assertIs<ShareDecision.NeedsApproval>(ask(e))
    }

    @Test fun `recipient restriction`() {
        val e = engine().also { it.grant(grant()).getOrThrow() }
        assertIs<ShareDecision.Deny>(ask(e, to = "+15559999999")) // default: only this conversation's contact
        assertIs<ShareDecision.Deny>(ask(e, to = null))
        e.grant(grant("g2", recipients = setOf("+15559999999"))).getOrThrow()
        assertIs<ShareDecision.Allow>(ask(e, to = "+15559999999"))
    }

    @Test fun `an incoming message can never authorise access, with or without a grant`() {
        val e = engine().also { it.grant(grant()).getOrThrow() }
        assertIs<ShareDecision.Deny>(ask(e, origin = Origin.INCOMING_MESSAGE))
        val none = engine()
        val x = none.requestExpansion(ShareRequest(a, FileOp.SEND, "$cats/a.jpg", a.contact, Origin.INCOMING_MESSAGE))
        assertTrue(x.triggeredByIncomingMessage)
        assertIs<ShareDecision.Deny>(ask(none)) // asking changed nothing
        assertEquals(1, none.pendingRequests().size)
        assertEquals(x.id, none.requestExpansion(ShareRequest(a, FileOp.SEND, "$cats/a.jpg", a.contact)).id) // collapsed
    }

    @Test fun `owner approval creates a grant only for the asking conversation`() {
        val e = engine()
        val x = e.requestExpansion(ShareRequest(a, FileOp.SEND, "$cats/a.jpg", a.contact))
        assertTrue(e.approveRequest(x.id, grant(subject = b)).isFailure)
        assertTrue(e.approveRequest(x.id, grant(scopes = listOf(ShareScope.File("$cats/a.jpg")))).isSuccess)
        assertIs<ShareDecision.Allow>(ask(e))
        assertIs<ShareDecision.Deny>(ask(e, path = "$cats/b.jpg")) // narrower than "the folder"
        assertTrue(e.pendingRequests().isEmpty())
    }

    @Test fun `emergency stop denies everything`() {
        var stop = false
        val e = engine { stop }.also { it.grant(grant()).getOrThrow() }
        assertIs<ShareDecision.Allow>(ask(e))
        stop = true
        assertIs<ShareDecision.Deny>(ask(e))
    }

    @Test fun `history records allow and deny`() {
        val e = engine().also { it.grant(grant()).getOrThrow() }
        ask(e)
        ask(e, path = "/etc/passwd")
        ask(e, s = b)
        val h = e.history("conv-A")
        assertEquals(listOf("allow", "deny"), h.map { it.outcome })
        assertEquals(1, e.history("conv-B").size)
    }

    @Test fun `state survives a restart through the JSON file store`() {
        val f = Files.createTempDirectory("share").resolve("share.json").toFile()
        val e1 = FileShareEngine(JsonFileShareStore(f), { now })
        e1.grant(grant()).getOrThrow()
        ask(e1)
        val e2 = FileShareEngine(JsonFileShareStore(f), { now })
        assertIs<ShareDecision.Allow>(ask(e2))
        e2.revokeConversation("conv-A")
        assertIs<ShareDecision.Deny>(ask(FileShareEngine(JsonFileShareStore(f), { now })))
        f.writeText("{broken")
        assertIs<ShareDecision.Deny>(ask(FileShareEngine(JsonFileShareStore(f), { now }))) // corrupt file fails closed
    }

    // ---- gated send path -------------------------------------------------------------------------------------------

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0x10) + ByteArray(20)

    private class Gw(val blob: MediaGateway.Blob?) : MediaGateway {
        var opened = 0
        override fun open(normalizedPath: String): MediaGateway.Blob? = blob.also { opened++ }
    }

    private fun gated(e: FileShareEngine, gw: Gw, inner: FakeBackend) = ShareGatedBackend(inner, e, gw, contactOf = { if (it == "conv-A") a.contact else b.contact })

    private fun args(conv: String = "conv-A", ref: String = "$cats/a.jpg") = mapOf("conversation" to conv, "attachment_ref" to ref)

    private fun ctx(autonomy: String = "approved") = BackendContext("app", "bot1", "r1", autonomy, null)

    @Test fun `gated send passes only a shared, valid file`() {
        val e = engine().also { it.grant(grant()).getOrThrow() }
        val inner = FakeBackend()
        val gw = Gw(MediaGateway.Blob("a.jpg", "image/jpeg", 500, jpeg))
        assertIs<BackendResult.Ok>(gated(e, gw, inner).call("communications.send_attachment", args(), ctx()))
        assertEquals(1, inner.calls.size)
    }

    @Test fun `gated send refuses an unshared file, never touches the gateway, and queues a request`() {
        val e = engine()
        val inner = FakeBackend()
        val gw = Gw(MediaGateway.Blob("a.jpg", "image/jpeg", 500, jpeg))
        val r = gated(e, gw, inner).call("communications.send_attachment", args(), ctx())
        assertEquals("file_not_shared", (r as BackendResult.Refused).code)
        assertEquals(0, gw.opened)
        assertTrue(inner.calls.isEmpty())
        assertEquals(1, e.pendingRequests().size)
    }

    @Test fun `gated send refuses a bare number and a conversation the file was not shared with`() {
        val e = engine().also { it.grant(grant()).getOrThrow() }
        val inner = FakeBackend()
        val g = gated(e, Gw(MediaGateway.Blob("a.jpg", "image/jpeg", 500, jpeg)), inner)
        assertEquals("share_needs_conversation", (g.call("communications.send_attachment", mapOf("to" to "5551230001", "attachment_ref" to "$cats/a.jpg"), ctx()) as BackendResult.Refused).code)
        assertEquals("file_not_shared", (g.call("communications.send_attachment", args(conv = "conv-B"), ctx()) as BackendResult.Refused).code)
        assertTrue(inner.calls.isEmpty())
    }

    @Test fun `grant needing approval cannot be used by an autonomous call`() {
        val e = engine().also { it.grant(grant(approval = true)).getOrThrow() }
        val inner = FakeBackend()
        val g = gated(e, Gw(MediaGateway.Blob("a.jpg", "image/jpeg", 500, jpeg)), inner)
        assertEquals("file_share_needs_approval", (g.call("communications.send_attachment", args(), ctx("auto")) as BackendResult.Refused).code)
        assertIs<BackendResult.Ok>(g.call("communications.send_attachment", args(), ctx("approved")))
    }

    @Test fun `gated send rejects disguised executables and oversize files`() {
        val e = engine().also { it.grant(grant(scopes = listOf(ShareScope.Folder(cats)))).getOrThrow() }
        val inner = FakeBackend()
        val elf = byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()) + ByteArray(30)
        val g1 = gated(e, Gw(MediaGateway.Blob("cute.jpg", "image/jpeg", 500, elf)), inner)
        assertEquals("attachment_rejected", (g1.call("communications.send_attachment", args(), ctx()) as BackendResult.Refused).code)
        val g2 = gated(e, Gw(MediaGateway.Blob("a.jpg", "image/jpeg", 5_000_000, jpeg)), inner)
        assertEquals("attachment_rejected", (g2.call("communications.send_attachment", args(), ctx()) as BackendResult.Refused).code)
        assertTrue(inner.calls.isEmpty())
    }

    @Test fun `other capabilities pass through untouched`() {
        val inner = FakeBackend()
        val g = gated(engine(), Gw(null), inner)
        assertIs<BackendResult.Ok>(g.call("communications.send_sms", mapOf("to" to "5551230001", "body" to "hi"), ctx()))
        assertEquals(1, inner.calls.size)
    }

    @Test fun `text cut in the middle of a character is still text, real garbage is not`() {
        val cut = "caf\u00e9 au lait".toByteArray().copyOf(4) // ends after the first byte of the two-byte "\u00e9"
        assertEquals("text/plain", AttachmentValidator.sniff(cut)!!.mime)
        assertNull(AttachmentValidator.sniff(byteArrayOf(0x68, 0x69, 0xC3.toByte(), 0x28))) // invalid UTF-8 in the middle
    }

    @Test fun `validator`() {
        val lim = ProviderMedia.limits("mms")!!
        assertNull(ProviderMedia.limits("sms"))
        assertNull(ProviderMedia.limits("textnow"))
        assertIs<AttachmentCheck.Ok>(AttachmentValidator.validate("x.jpg", "image/jpeg", jpeg, 10, lim))
        assertIs<AttachmentCheck.Rejected>(AttachmentValidator.validate("x.jpg", "image/png", jpeg, 10, lim)) // declared != content
        assertIs<AttachmentCheck.Rejected>(AttachmentValidator.validate("x.jpg.apk", null, jpeg, 10, lim))
        assertIs<AttachmentCheck.Rejected>(AttachmentValidator.validate("x.jpg", null, jpeg, 0, lim))
        assertIs<AttachmentCheck.Rejected>(AttachmentValidator.validate("x.jpg", null, jpeg, 10, null))
        assertIs<AttachmentCheck.Rejected>(AttachmentValidator.validate("x.sh", null, "#!/bin/sh\nrm -rf".toByteArray(), 10, lim))
        assertEquals("passwd", AttachmentValidator.safeName("../../etc/passwd"))
        assertEquals("attachment", AttachmentValidator.safeName("..."))
        assertEquals(MediaKind.VIDEO, AttachmentValidator.sniff(byteArrayOf(0, 0, 0, 0x18) + "ftypmp42".toByteArray())!!.kind)
        assertEquals("audio/amr", AttachmentValidator.sniff("#!AMR\n".toByteArray())!!.mime)
    }
}
