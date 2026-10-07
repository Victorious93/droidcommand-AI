package ai.droidcommand.conversations

import ai.droidcommand.agent.ConversationContext
import ai.droidcommand.agent.Role
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Real Room over Robolectric's in-memory SQLite, on the JVM — so the DAO queries, the cascade delete
 * and the replace transaction actually execute. NOT an on-device test: it does not cover the file-backed
 * database, migrations, or real Android SQLite differences. No `connectedAndroidTest` has been run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomConversationStoreTest {
    private lateinit var db: ConversationDatabase
    private lateinit var store: RoomConversationStore
    private var now = 1_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ConversationDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = RoomConversationStore(db.conversationDao()) { now++ }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun convo(vararg pairs: Pair<Role, String>, system: String? = null, max: Int? = null) =
        ConversationContext(system, max).also { c -> pairs.forEach { (r, t) -> c.append(r, t) } }

    @Test
    fun `save then load round trips messages in order with system prompt and budget`() {
        store.save("c1", convo(Role.USER to "hi", Role.ASSISTANT to "hello", Role.USER to "more ünïcode", system = "be terse", max = 5_000))

        val loaded = assertNotNull(store.load("c1"))

        assertEquals("be terse", loaded.systemPrompt)
        assertEquals(5_000, loaded.maxTokens)
        assertEquals(listOf(Role.USER to "hi", Role.ASSISTANT to "hello", Role.USER to "more ünïcode"), loaded.messages.map { it.role to it.content })
    }

    @Test
    fun `saving again replaces the messages rather than appending`() {
        store.save("c1", convo(Role.USER to "a", Role.ASSISTANT to "b"))
        store.save("c1", convo(Role.USER to "only"))

        assertEquals(listOf("only"), store.load("c1")!!.messages.map { it.content })
        assertEquals(1, db.conversationDao().messages("c1").size)
    }

    @Test
    fun `an unknown id loads as null and list is sorted`() {
        assertNull(store.load("missing"))
        store.save("b", convo(Role.USER to "x"))
        store.save("a", convo(Role.USER to "x"))

        assertEquals(listOf("a", "b"), store.list())
    }

    @Test
    fun `delete removes the conversation and cascades its messages`() {
        store.save("c1", convo(Role.USER to "a", Role.ASSISTANT to "b"))
        store.save("c2", convo(Role.USER to "keep"))

        assertTrue(store.delete("c1"))
        assertFalse(store.delete("c1"))

        assertNull(store.load("c1"))
        assertEquals(0, db.conversationDao().messages("c1").size)
        assertEquals(1, db.conversationDao().messages("c2").size)
    }

    @Test
    fun `ids are validated like the JSON store`() {
        for (bad in listOf("", "../x", "a b", "a/b")) {
            assertFailsWith<IllegalArgumentException>(bad) { store.save(bad, convo(Role.USER to "x")) }
            assertFailsWith<IllegalArgumentException>(bad) { store.load(bad) }
        }
    }

    @Test
    fun `an unknown stored role is an IOException not a silent default`() {
        store.save("c1", convo(Role.USER to "x"))
        val dao = db.conversationDao()
        dao.deleteMessages("c1")
        dao.insertMessages(listOf(MessageEntity(conversationId = "c1", position = 0, role = "WIZARD", content = "x")))

        assertFailsWith<IOException> { store.load("c1") }
    }

    @Test
    fun `load re-applies the token budget to over-budget stored rows`() {
        val dao = db.conversationDao()
        val big = "x".repeat(4_000) // ~1000 estimated tokens each, far over the 100-token budget
        dao.replace(
            ConversationEntity("c1", null, 100, 1L),
            listOf(
                MessageEntity(conversationId = "c1", position = 0, role = "USER", content = big),
                MessageEntity(conversationId = "c1", position = 1, role = "ASSISTANT", content = big),
                MessageEntity(conversationId = "c1", position = 2, role = "USER", content = "last"),
            ),
        )

        val loaded = store.load("c1")!!

        assertEquals(listOf("last"), loaded.messages.map { it.content })
    }

    @Test
    fun `a second save of the same id bumps updated_at`() {
        store.save("c1", convo(Role.USER to "a"))
        val first = db.conversationDao().find("c1")!!.updatedAt
        store.save("c1", convo(Role.USER to "a"))

        assertTrue(db.conversationDao().find("c1")!!.updatedAt > first)
    }
}
