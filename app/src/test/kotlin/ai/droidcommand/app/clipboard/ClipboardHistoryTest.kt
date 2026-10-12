package ai.droidcommand.app.clipboard

import ai.droidcommand.config.EncryptedSecretsVault
import ai.droidcommand.config.InMemorySecretRecordStorage
import ai.droidcommand.config.JceAesGcmCipher
import javax.crypto.KeyGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClipboardHistoryTest {
    /** Records what was saved; [failSaves] simulates a write that the store refuses. */
    private class FakeStorage(initial: List<String>? = emptyList()) : PrivateClipStorage {
        var stored: List<String>? = initial
        var failSaves = false
        var saves = 0

        override fun load(): List<String>? = stored

        override fun save(texts: List<String>) {
            check(!failSaves) { "write refused" }
            saves++
            stored = texts
        }
    }

    @Test
    fun `public list is newest first and capped at its limit`() {
        val history = ClipboardHistory(publicLimit = { 2 }, storage = FakeStorage())
        history.addPublic("a")
        history.addPublic("b")
        history.addPublic("c")
        assertEquals(listOf("c", "b"), history.publicClips().map { it.text })
    }

    @Test
    fun `lowering the limit drops the oldest public clips`() {
        var limit = 5
        val history = ClipboardHistory(publicLimit = { limit }, storage = FakeStorage())
        listOf("a", "b", "c", "d").forEach { history.addPublic(it) }
        limit = 2
        assertEquals(listOf("d", "c"), history.publicClips().map { it.text })
    }

    @Test
    fun `blank text and an immediate repeat are ignored`() {
        val history = ClipboardHistory(publicLimit = { 5 }, storage = FakeStorage())
        assertFalse(history.addPublic("   "))
        assertTrue(history.addPublic("x"))
        assertFalse(history.addPublic("x"))
        assertEquals(1, history.publicClips().size)
    }

    @Test
    fun `moving a clip persists it and removes it from the public list`() {
        val storage = FakeStorage()
        val history = ClipboardHistory(publicLimit = { 5 }, storage = storage)
        history.addPublic("card 4111")
        assertTrue(history.moveToPrivate(history.publicClips().single().id))
        assertTrue(history.publicClips().isEmpty())
        assertEquals(listOf("card 4111"), history.privateClips().map { it.text })
        assertEquals(listOf("card 4111"), storage.stored)
    }

    @Test
    fun `a refused write leaves both lists unchanged`() {
        val storage = FakeStorage().apply { failSaves = true }
        val history = ClipboardHistory(publicLimit = { 5 }, storage = storage)
        history.addPublic("keep me")
        assertFalse(history.moveToPrivate(history.publicClips().single().id))
        assertEquals(listOf("keep me"), history.publicClips().map { it.text })
        assertTrue(history.privateClips().isEmpty())
    }

    @Test
    fun `private list refuses to grow past its limit`() {
        val history = ClipboardHistory(publicLimit = { 5 }, storage = FakeStorage(), privateLimit = 1)
        history.addPublic("one")
        history.addPublic("two")
        val (newer, older) = history.publicClips().let { it[0].id to it[1].id }
        assertTrue(history.moveToPrivate(older))
        assertFalse(history.moveToPrivate(newer))
        assertEquals(listOf("two"), history.publicClips().map { it.text })
    }

    @Test
    fun `an unreadable private store blocks moves and is never overwritten`() {
        val storage = FakeStorage(initial = null)
        val history = ClipboardHistory(publicLimit = { 5 }, storage = storage)
        assertTrue(history.privateUnreadable)
        history.addPublic("clip")
        assertFalse(history.moveToPrivate(history.publicClips().single().id))
        assertEquals(0, storage.saves)
        assertNull(storage.stored)
    }

    @Test
    fun `discardPrivate erases an unreadable store`() {
        val storage = FakeStorage(initial = null)
        val history = ClipboardHistory(publicLimit = { 5 }, storage = storage)
        assertTrue(history.discardPrivate())
        assertFalse(history.privateUnreadable)
        assertEquals(emptyList<String>(), storage.stored)
    }

    @Test
    fun `deleting a private clip persists the removal`() {
        val storage = FakeStorage(initial = listOf("a", "b"))
        val history = ClipboardHistory(publicLimit = { 5 }, storage = storage)
        assertTrue(history.deletePrivate(history.privateClips().first().id))
        assertEquals(listOf("b"), storage.stored)
        assertEquals(listOf("b"), history.privateClips().map { it.text })
    }

    @Test
    fun `codec round-trips commas, newlines and non-ASCII text`() {
        val texts = listOf("a,b", "line1\nline2", "héllo ✓")
        assertEquals(texts, PrivateClipCodec.decode(PrivateClipCodec.encode(texts)))
        assertEquals(emptyList<String>(), PrivateClipCodec.decode(PrivateClipCodec.encode(emptyList())))
    }

    @Test
    fun `codec rejects data it did not write`() {
        assertNull(PrivateClipCodec.decode("not base64!"))
    }

    @Test
    fun `vault storage round-trips and keeps plaintext out of the stored records`() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val records = InMemorySecretRecordStorage()
        val storage = VaultPrivateClipStorage(EncryptedSecretsVault(storage = records, cipher = JceAesGcmCipher { key }))
        storage.save(listOf("hunter2-secret"))
        assertEquals(listOf("hunter2-secret"), storage.load())
        assertTrue(records.keys().isNotEmpty())
        assertTrue(records.keys().all { records.read(it)?.contains("hunter2") != true })
    }
}
