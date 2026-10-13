package ai.droidcommand.app.prompts

import ai.droidcommand.templates.PromptTemplate
import ai.droidcommand.templates.Skill
import ai.droidcommand.templates.TemplateCategory
import ai.droidcommand.templates.android.TemplateStores
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The on-device template and skill library. Bundled items are seeded by [TemplateStores.open].
 * Built-in rows cannot be deleted. Nothing here calls a model.
 */
@Singleton
class PromptLibrary @Inject constructor(@ApplicationContext context: Context) {
    // Opened on first use. Seeding reads Room, which rejects the main thread, so callers must be off it.
    private val stores by lazy { TemplateStores.open(context) }

    fun templates(): List<PromptTemplate> = stores.templates.list()

    fun skills(): List<Skill> = stores.skills.list()

    fun saveTemplate(name: String, body: String): PromptTemplate {
        val template = PromptTemplate(
            id = "user-" + UUID.randomUUID().toString().replace("-", "").take(16),
            name = name.trim(),
            category = TemplateCategory.CUSTOM,
            body = body.trim(),
            builtIn = false,
        )
        stores.templates.save(template)
        return template
    }

    fun deleteTemplate(id: String): Boolean = stores.templates.delete(id)

    fun saveSkill(name: String, systemPrompt: String): Skill {
        val skill = Skill(
            id = "user-" + UUID.randomUUID().toString().replace("-", "").take(16),
            name = name.trim(),
            description = "Saved on this device.",
            systemPrompt = systemPrompt.trim(),
            builtIn = false,
        )
        stores.skills.save(skill)
        return skill
    }

    fun deleteSkill(id: String): Boolean = stores.skills.delete(id)
}
