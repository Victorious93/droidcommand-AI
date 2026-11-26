package ai.droidcommand.communications

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** When a share stops, the engine tells the app which picked references nothing covers any more, so Android's read access can be released. */
class ReleaseTest {
    private val ext = "content://com.android.externalstorage.documents"
    private val tree = "$ext/tree/primary%3ADCIM%2FCats"
    private val item = "$ext/document/primary%3ADCIM%2FDogs%2Fa.jpg"
    private var now = 1_000L
    private val released = mutableListOf<Set<String>>()
    private val engine = FileShareEngine(clock = { now }, onContentReleased = { released.add(it) })

    private fun grant(id: String, conv: String, vararg scopes: ShareScope, exp: Long? = null) =
        ShareGrant(id, ShareSubject(conv), scopes.toList(), setOf(FileOp.SEND), expiresAtMs = exp, createdAtMs = 1_000L)

    @Test fun `revoking the only grant over a picked folder releases it`() {
        engine.grant(grant("g1", "A", ShareScope.Tree(tree))).getOrThrow()
        assertTrue(released.isEmpty(), "granting releases nothing")
        engine.revoke("g1")
        assertEquals(listOf(setOf(ContentRefs.canonical(tree)!!)), released)
    }

    @Test fun `a reference two active grants share is released only when the last one goes`() {
        engine.grant(grant("g1", "A", ShareScope.Tree(tree))).getOrThrow()
        engine.grant(grant("g2", "B", ShareScope.Tree(tree))).getOrThrow()
        engine.revoke("g1")
        assertTrue(released.isEmpty(), "g2 still covers it")
        engine.revoke("g2")
        assertEquals(1, released.size)
    }

    @Test fun `revoking a whole conversation releases what only it covered`() {
        engine.grant(grant("g1", "A", ShareScope.Tree(tree))).getOrThrow()
        engine.grant(grant("g2", "A", ShareScope.Item(item))).getOrThrow()
        engine.grant(grant("g3", "B", ShareScope.Item(item))).getOrThrow()
        assertEquals(2, engine.revokeConversation("A"))
        assertEquals(listOf(setOf(ContentRefs.canonical(tree)!!)), released, "the item is still covered by B")
    }

    @Test fun `plain paths are never reported and collection members are split by kind`() {
        engine.grant(grant("g1", "A", ShareScope.Folder("/sdcard/DCIM"), ShareScope.Collection("c", setOf("/sdcard/x.jpg", item)))).getOrThrow()
        engine.revoke("g1")
        assertEquals(listOf(setOf(ContentRefs.canonical(item)!!)), released)
    }

    @Test fun `an expired grant holds nothing, so it neither blocks nor causes a release`() {
        engine.grant(grant("old", "A", ShareScope.Tree(tree), exp = 2_000L)).getOrThrow()
        engine.grant(grant("live", "B", ShareScope.Tree(tree))).getOrThrow()
        now = 3_000L
        engine.revoke("live")
        assertEquals(1, released.size, "the expired grant does not keep it alive")
    }

    @Test fun `replacing a grant with fewer scopes releases the dropped one`() {
        engine.grant(grant("g1", "A", ShareScope.Tree(tree), ShareScope.Item(item))).getOrThrow()
        engine.grant(grant("g1", "A", ShareScope.Tree(tree))).getOrThrow()
        assertEquals(listOf(setOf(ContentRefs.canonical(item)!!)), released)
    }

    @Test fun `a failing callback never undoes the revocation`() {
        val e = FileShareEngine(clock = { now }, onContentReleased = { error("boom") })
        e.grant(grant("g1", "A", ShareScope.Tree(tree))).getOrThrow()
        assertTrue(e.revoke("g1"))
        assertFalse(e.grants().single().let { !it.revoked })
        val d = e.decide(ShareRequest(ShareSubject("A"), FileOp.SEND, "$tree/document/primary%3ADCIM%2FCats%2Fa.jpg", "x"))
        assertTrue(d is ShareDecision.Deny)
    }

    @Test fun `revoking something already revoked or unknown releases nothing`() {
        engine.grant(grant("g1", "A", ShareScope.Tree(tree))).getOrThrow()
        engine.revoke("g1")
        released.clear()
        assertFalse(engine.revoke("g1"))
        assertFalse(engine.revoke("nope"))
        assertEquals(0, engine.revokeConversation("A"))
        assertTrue(released.isEmpty())
    }
}
