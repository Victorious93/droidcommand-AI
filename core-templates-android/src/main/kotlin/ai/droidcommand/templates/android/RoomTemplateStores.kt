package ai.droidcommand.templates.android

import ai.droidcommand.llm.ProviderType
import ai.droidcommand.templates.BundledContent
import ai.droidcommand.templates.PromptTemplate
import ai.droidcommand.templates.Skill
import ai.droidcommand.templates.SkillStore
import ai.droidcommand.templates.TemplateCategory
import ai.droidcommand.templates.TemplateStore
import android.content.Context
import androidx.room.Room

/**
 * Room-backed [TemplateStore]. Built-in templates cannot be deleted ([delete] returns `false`):
 * [BundledContent.seed] re-adds any bundled id that is missing, so a deleted built-in would silently
 * come back on the next launch. A user can still edit one (save with the same id); an edit survives
 * re-seeding because seeding only adds absent ids.
 *
 * A stored category this build does not know (e.g. written by a newer version) reads back as
 * [TemplateCategory.CUSTOM] instead of crashing.
 */
class RoomTemplateStore(private val dao: TemplateDao) : TemplateStore {
    override fun save(template: PromptTemplate) = dao.upsert(template.toEntity())

    override fun get(id: String): PromptTemplate? = dao.find(id)?.toModel()

    override fun list(): List<PromptTemplate> = dao.all().map { it.toModel() }

    override fun delete(id: String): Boolean = dao.deleteUserTemplate(id) > 0
}

/** Room-backed [SkillStore]; same built-in deletion rule and unknown-value tolerance as [RoomTemplateStore]. */
class RoomSkillStore(private val dao: SkillDao) : SkillStore {
    override fun save(skill: Skill) = dao.upsert(skill.toEntity())

    override fun get(id: String): Skill? = dao.find(id)?.toModel()

    override fun list(): List<Skill> = dao.all().map { it.toModel() }

    override fun delete(id: String): Boolean = dao.deleteUserSkill(id) > 0
}

/**
 * Opens the on-disk database and seeds the bundled templates/skills on first use (idempotent; never
 * overwrites a user edit). Seeding does a read-then-write per id, which is fine for the single
 * first-launch caller this is meant for but is not safe against concurrent seeders.
 */
class TemplateStores private constructor(val templates: RoomTemplateStore, val skills: RoomSkillStore, val database: TemplatesDatabase) {
    companion object {
        const val DATABASE_NAME = "templates.db"

        fun open(context: Context): TemplateStores = from(Room.databaseBuilder(context, TemplatesDatabase::class.java, DATABASE_NAME).build())

        fun from(database: TemplatesDatabase): TemplateStores {
            val stores = TemplateStores(RoomTemplateStore(database.templateDao()), RoomSkillStore(database.skillDao()), database)
            BundledContent.seed(stores.templates, stores.skills)
            return stores
        }
    }
}

private fun PromptTemplate.toEntity() = TemplateEntity(id, name, category.name, body, builtIn)

private fun TemplateEntity.toModel() = PromptTemplate(
    id = id,
    name = name,
    category = TemplateCategory.entries.firstOrNull { it.name == category } ?: TemplateCategory.CUSTOM,
    body = body,
    builtIn = builtIn,
)

private fun Skill.toEntity() = SkillEntity(id, name, description, systemPrompt, preferredProviderType?.name, preferredModel, builtIn)

private fun SkillEntity.toModel() = Skill(
    id = id,
    name = name,
    description = description,
    systemPrompt = systemPrompt,
    preferredProviderType = ProviderType.entries.firstOrNull { it.name == preferredProviderType },
    preferredModel = preferredModel,
    builtIn = builtIn,
)
