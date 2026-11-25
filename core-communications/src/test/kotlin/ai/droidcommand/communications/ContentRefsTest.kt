package ai.droidcommand.communications

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContentRefsTest {
    private val ext = "content://com.android.externalstorage.documents"
    private val tree = "$ext/tree/primary%3ADCIM%2FCats"
    private fun doc(id: String, treeId: String = "primary%3ADCIM%2FCats") = "$ext/tree/$treeId/document/$id"
    private fun ref(s: String) = ContentRefs.parse(s)!!

    @Test fun `parses the three SAF shapes and rebuilds one canonical spelling`() {
        assertEquals("primary:DCIM/Cats", ref(tree).treeId)
        assertEquals("primary:DCIM/Cats/a.jpg", ref("$ext/document/primary%3ADCIM%2FCats%2Fa.jpg").docId)
        val d = ref(doc("primary%3ADCIM%2FCats%2Fa.jpg"))
        assertEquals("primary:DCIM/Cats", d.treeId)
        assertEquals("primary:DCIM/Cats/a.jpg", d.docId)
        // Lower-case hex and an unneeded-encoded character are the same document as the canonical spelling.
        assertEquals(ref(doc("primary%3ADCIM%2FCats%2Fa.jpg")).canonical, ref(doc("primary%3aDCIM%2fCats%2fa.jpg")).canonical)
        assertEquals(ref(tree).canonical, ref("$ext/tree/primary%3aDCIM%2fCats").canonical)
    }

    @Test fun `refuses unknown providers, other schemes and anything that can smuggle meaning`() {
        val bad = listOf(
            "file:///sdcard/DCIM/a.jpg", "http://x/y", "content://evil.app.provider/document/x", "content://com.android.externalstorage.documents@evil/tree/a",
            "$tree?x=1", "$tree#frag", "$ext/tree/primary%3A..%2F..%2Fsecret", "$ext/document/primary%3ADCIM%2F..%2FSecret%2Fa.jpg",
            "$ext/document/primary%3ADCIM%2F.%2Fa.jpg", "$ext/document/primary%253ADCIM", "$ext/document/%00", "$ext/document/a%5Cb", "$ext/document/%zz",
            "$ext/document/a%2", "$ext/tree//document/x", "$ext/tree/primary%3AX/document", "$ext/document/", "content://", "", " $tree",
            "$ext/document/caf\u00e9", "$ext/document/a b",
        )
        for (b in bad) assertNull(ContentRefs.parse(b), b)
    }

    @Test fun `a document is inside its tree only on a folder boundary`() {
        val t = ref(tree)
        assertTrue(ContentRefs.within(ref(doc("primary%3ADCIM%2FCats%2Fa.jpg")), t, subfolders = false))
        assertTrue(ContentRefs.within(ref("$ext/document/primary%3ADCIM%2FCats%2Fa.jpg"), t, subfolders = false))
        assertFalse("nested needs subfolders", ContentRefs.within(ref(doc("primary%3ADCIM%2FCats%2Fsub%2Fa.jpg")), t, false))
        assertTrue(ContentRefs.within(ref(doc("primary%3ADCIM%2FCats%2Fsub%2Fdeep%2Fa.jpg")), t, true))
        assertFalse("sibling with the same prefix", ContentRefs.within(ref(doc("primary%3ADCIM%2FCatsPrivate%2Fa.jpg")), t, true))
        assertFalse("parent folder", ContentRefs.within(ref("$ext/document/primary%3ADCIM%2Fa.jpg"), t, true))
        assertFalse("another tree", ContentRefs.within(ref(doc("primary%3ADCIM%2FCats%2Fa.jpg", treeId = "primary%3ADCIM")), t, true))
        assertFalse("another provider", ContentRefs.within(ref("content://com.android.providers.downloads.documents/document/primary%3ADCIM%2FCats%2Fa.jpg"), t, true))
        assertTrue("the tree itself can be browsed", ContentRefs.within(ref(tree), t, false))
    }

    @Test fun `provider roots are recognised so they are never shared whole`() {
        assertTrue(ContentRefs.isRoot(ref("$ext/tree/primary%3A")))
        assertFalse(ContentRefs.isRoot(ref(tree)))
    }

    @Test fun `treeOf strips the document part`() {
        assertEquals(ref(tree).canonical, ContentRefs.treeOf(doc("primary%3ADCIM%2FCats%2Fa.jpg"))!!.canonical)
        assertNull(ContentRefs.treeOf("$ext/document/primary%3ADCIM%2FCats%2Fa.jpg"))
    }

    // ---- through the engine ------------------------------------------------------------------------------------------

    private val subject = ShareSubject("conv-A", "bot1", "+15551230001")
    private fun engine() = FileShareEngine(clock = { 1_000L })
    private fun grant(vararg scopes: ShareScope, ops: Set<FileOp> = setOf(FileOp.SEND, FileOp.BROWSE)) =
        ShareGrant("g", subject, scopes.toList(), ops, requireApproval = false, createdAtMs = 1_000L)

    private fun ask(e: FileShareEngine, path: String, s: ShareSubject = subject) = e.decide(ShareRequest(s, FileOp.SEND, path, "+15551230001"))

    @Test fun `a tree grant covers only documents inside that tree`() {
        val e = engine().also { it.grant(grant(ShareScope.Tree(tree, subfolders = false))).getOrThrow() }
        assertIs<ShareDecision.Allow>(ask(e, doc("primary%3ADCIM%2FCats%2Fa.jpg")))
        assertIs<ShareDecision.Allow>(ask(e, doc("primary%3aDCIM%2fCats%2fa.jpg"))) // another spelling of the same document
        assertIs<ShareDecision.Deny>(ask(e, doc("primary%3ADCIM%2FCats%2Fsub%2Fa.jpg")))
        assertIs<ShareDecision.Deny>(ask(e, doc("primary%3ADCIM%2FCatsPrivate%2Fa.jpg")))
        assertIs<ShareDecision.Deny>(ask(e, "$ext/document/primary%3ADCIM%2F..%2FSecret%2Fa.jpg"))
        assertIs<ShareDecision.Deny>(ask(e, "/storage/emulated/0/DCIM/Cats/a.jpg")) // a plain path is not covered by a tree grant
        assertIs<ShareDecision.Deny>(ask(e, doc("primary%3ADCIM%2FCats%2Fa.jpg"), s = ShareSubject("conv-B", "bot1", "+15551230001")))
    }

    @Test fun `a plain-path grant does not cover a content reference of the same name`() {
        val e = engine().also { it.grant(grant(ShareScope.Folder("/storage/emulated/0/DCIM/Cats", true))).getOrThrow() }
        assertIs<ShareDecision.Deny>(ask(e, doc("primary%3ADCIM%2FCats%2Fa.jpg")))
    }

    @Test fun `an item grant matches that exact document and a collection matches its members`() {
        val one = "$ext/document/primary%3ADCIM%2FCats%2Fa.jpg"
        val two = "$ext/document/primary%3ADCIM%2FCats%2Fb.jpg"
        val e = engine()
        e.grant(grant(ShareScope.Item(one))).getOrThrow()
        assertIs<ShareDecision.Allow>(ask(e, one))
        assertIs<ShareDecision.Deny>(ask(e, two))
        e.grant(grant(ShareScope.Collection("album", setOf(two, "/storage/emulated/0/x.jpg"))).copy(id = "g2")).getOrThrow()
        assertIs<ShareDecision.Allow>(ask(e, two))
        assertIs<ShareDecision.Allow>(ask(e, "/storage/emulated/0/x.jpg"))
    }

    @Test fun `unsafe or root scopes are refused at grant time`() {
        assertTrue(engine().grant(grant(ShareScope.Tree("$ext/tree/primary%3A"))).isFailure)
        assertTrue(engine().grant(grant(ShareScope.Tree("content://evil/tree/x"))).isFailure)
        assertTrue(engine().grant(grant(ShareScope.Item("file:///sdcard/a.jpg"))).isFailure)
        assertTrue(engine().grant(grant(ShareScope.Item(tree))).isFailure) // a bare tree is not a file
        assertTrue(engine().grant(grant(ShareScope.Collection("c", setOf("$ext/document/primary%3A..%2Fx")))).isFailure)
    }

    @Test fun `grants with the new scopes survive a restart through the JSON store`() {
        val f = java.nio.file.Files.createTempDirectory("share").resolve("s.json").toFile()
        FileShareEngine(JsonFileShareStore(f), { 1_000L }).grant(grant(ShareScope.Tree(tree, true), ShareScope.Item("$ext/document/primary%3AX%2Fa.jpg"))).getOrThrow()
        val again = FileShareEngine(JsonFileShareStore(f), { 1_000L })
        assertIs<ShareDecision.Allow>(ask(again, doc("primary%3ADCIM%2FCats%2Fsub%2Fa.jpg")))
        assertNotNull(again.grants().single().scopes.filterIsInstance<ShareScope.Item>().singleOrNull())
    }

    // ---- the owner form ----------------------------------------------------------------------------------------------

    private fun build(f: ShareForm) = ShareForms.build(f, 1_000L) { "id" }
    private val base = ShareForm("conv-A", ops = setOf(FileOp.SEND))

    @Test fun `form builds tree, single and multiple picked scopes`() {
        assertEquals(ShareScope.Tree(ref(tree).canonical, false), build(base.copy(kind = ShareKind.TREE, path = tree)).getOrThrow().scopes.single())
        assertEquals(true, (build(base.copy(kind = ShareKind.TREE_WITH_SUBFOLDERS, path = doc("primary%3ADCIM%2FCats%2Fa.jpg"))).getOrThrow().scopes.single() as ShareScope.Tree).subfolders)
        val one = "$ext/document/primary%3ADCIM%2FCats%2Fa.jpg"
        assertIs<ShareScope.Item>(build(base.copy(kind = ShareKind.PICKED, path = one)).getOrThrow().scopes.single())
        val many = build(base.copy(kind = ShareKind.PICKED, path = "$one\n$ext/document/primary%3ADCIM%2FCats%2Fb.jpg\n$one")).getOrThrow().scopes.single()
        assertEquals(2, (many as ShareScope.Collection).files.size)
    }

    @Test fun `form refuses roots, foreign references and empty picks`() {
        assertTrue(build(base.copy(kind = ShareKind.TREE, path = "$ext/tree/primary%3A")).isFailure)
        assertTrue(build(base.copy(kind = ShareKind.TREE, path = "/storage/emulated/0/DCIM")).isFailure)
        assertTrue(build(base.copy(kind = ShareKind.PICKED, path = "file:///sdcard/a.jpg")).isFailure)
        assertTrue(build(base.copy(kind = ShareKind.PICKED, path = "  \n ")).isFailure)
        assertTrue(build(base.copy(kind = ShareKind.PICKED, path = (1..201).joinToString("\n") { "$ext/document/primary%3AX%2F$it.jpg" })).isFailure)
    }
}
