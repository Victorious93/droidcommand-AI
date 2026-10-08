package ai.droidcommand.templates.android

import ai.droidcommand.llm.ProviderType
import ai.droidcommand.templates.BundledContent
import ai.droidcommand.templates.PromptTemplate
import ai.droidcommand.templates.Skill
import ai.droidcommand.templates.TemplateCategory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Real Room over Robolectric's SQLite, on the JVM. NOT an on-device test: no migrations, no real
 * Android SQLite quirks, no `connectedAndroidTest`. The reopen test uses a file-backed database in
 * Robolectric's sandbox, which shows data survives closing and reopening, not a process restart.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomTemplateStoresTest {
    private lateinit var db: TemplatesDatabase
    private lateinit var templates: RoomTemplateStore
    private lateinit var skills: RoomSkillStore

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), TemplatesDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        templates = RoomTemplateStore(db.templateDao())
        skills = RoomSkillStore(db.skillDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun tpl(id: String, name: String = id, body: String = "{{x}}", builtIn: Boolean = false, c: TemplateCategory = TemplateCategory.CUSTOM) =
        PromptTemplate(id, name, c, body, builtIn)

    @Test
    fun `template round trips and save with the same id replaces`() {
        templates.save(tpl("a", body = "one {{x}}", c = TemplateCategory.CODING))
        assertEquals(tpl("a", body = "one {{x}}", c = TemplateCategory.CODING), templates.get("a"))
        templates.save(tpl("a", body = "two {{x}}"))
        assertEquals("two {{x}}", templates.get("a")?.body)
        assertEquals(1, templates.list().size)
        assertNull(templates.get("missing"))
    }

    @Test
    fun `skill round trips including the provider hint and model`() {
        val s = Skill("s1", "Skill", "d", "prompt", ProviderType.LOCAL, "tiny", builtIn = false)
        skills.save(s)
        assertEquals(s, skills.get("s1"))
        skills.save(s.copy(preferredProviderType = null, preferredModel = null))
        assertEquals(null, skills.get("s1")?.preferredProviderType)
        assertEquals(null, skills.get("s1")?.preferredModel)
    }

    @Test
    fun `list order is deterministic - built-ins first then name case-insensitively then id`() {
        templates.save(tpl("u2", name = "banana"))
        templates.save(tpl("b1", name = "Zeta", builtIn = true))
        templates.save(tpl("u1", name = "Apple"))
        templates.save(tpl("b0", name = "alpha", builtIn = true))
        templates.save(tpl("u3", name = "apple"))
        assertEquals(listOf("b0", "b1", "u1", "u3", "u2"), templates.list().map { it.id })
    }

    @Test
    fun `user items can be deleted and built-ins cannot`() {
        templates.save(tpl("mine"))
        templates.save(tpl("core", builtIn = true))
        assertTrue(templates.delete("mine"))
        assertFalse(templates.delete("mine"))
        assertFalse(templates.delete("core"))
        assertNotNull(templates.get("core"))
        skills.save(Skill("sk-core", "n", "d", "p", builtIn = true))
        assertFalse(skills.delete("sk-core"))
        assertNotNull(skills.get("sk-core"))
    }

    @Test
    fun `unknown stored category or provider type reads back as a safe default instead of crashing`() {
        db.templateDao().upsert(TemplateEntity("f", "n", "FROM_A_NEWER_VERSION", "b", false))
        assertEquals(TemplateCategory.CUSTOM, templates.get("f")?.category)
        db.skillDao().upsert(SkillEntity("f", "n", "d", "p", "QUANTUM", "m", false))
        assertNull(skills.get("f")?.preferredProviderType)
        assertEquals("m", skills.get("f")?.preferredModel)
    }

    @Test
    fun `first-use seeding adds the bundled content and is idempotent`() {
        TemplateStores.from(db)
        assertEquals(BundledContent.templates.size, templates.list().size)
        assertEquals(BundledContent.skills.size, skills.list().size)
        assertTrue(templates.list().all { it.builtIn })
        TemplateStores.from(db) // second launch
        assertEquals(BundledContent.templates.size, templates.list().size)
    }

    @Test
    fun `a user edit of a built-in survives re-seeding`() {
        TemplateStores.from(db)
        val first = BundledContent.templates.first()
        templates.save(first.copy(body = "my edit {{x}}"))
        TemplateStores.from(db)
        assertEquals("my edit {{x}}", templates.get(first.id)?.body)
    }

    @Test
    fun `user content and bundled content coexist after seeding`() {
        TemplateStores.from(db)
        templates.save(tpl("user.1", name = "Mine"))
        assertEquals(BundledContent.templates.size + 1, templates.list().size)
        assertEquals("user.1", templates.list().last { !it.builtIn }.id)
    }

    @Test
    fun `data in a file-backed database survives close and reopen`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "reopen-test.db"
        ctx.deleteDatabase(name)
        val first = Room.databaseBuilder(ctx, TemplatesDatabase::class.java, name).allowMainThreadQueries().build()
        val stores = TemplateStores.from(first)
        stores.templates.save(tpl("kept", body = "persist {{x}}"))
        stores.skills.save(Skill("kept-skill", "K", "d", "prompt", ProviderType.LOCAL, null))
        first.close()

        val second = Room.databaseBuilder(ctx, TemplatesDatabase::class.java, name).allowMainThreadQueries().build()
        try {
            assertEquals("persist {{x}}", RoomTemplateStore(second.templateDao()).get("kept")?.body)
            assertEquals(ProviderType.LOCAL, RoomSkillStore(second.skillDao()).get("kept-skill")?.preferredProviderType)
            assertEquals(BundledContent.templates.size + 1, RoomTemplateStore(second.templateDao()).list().size)
        } finally {
            second.close()
            ctx.deleteDatabase(name)
        }
    }
}
