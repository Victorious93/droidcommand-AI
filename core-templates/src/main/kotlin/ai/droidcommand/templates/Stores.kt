package ai.droidcommand.templates

interface TemplateStore {
    fun save(template: PromptTemplate)

    fun get(id: String): PromptTemplate?

    fun list(): List<PromptTemplate>

    fun delete(id: String): Boolean
}

interface SkillStore {
    fun save(skill: Skill)

    fun get(id: String): Skill?

    fun list(): List<Skill>

    fun delete(id: String): Boolean
}

/**
 * In-memory only: nothing here survives a restart. The Room-backed stores
 * the roadmap calls for need the Android SDK and are not built.
 */
class InMemoryTemplateStore : TemplateStore {
    private val items = LinkedHashMap<String, PromptTemplate>()

    @Synchronized override fun save(template: PromptTemplate) {
        items[template.id] = template
    }

    @Synchronized override fun get(id: String): PromptTemplate? = items[id]

    @Synchronized override fun list(): List<PromptTemplate> = items.values.toList()

    @Synchronized override fun delete(id: String): Boolean = items.remove(id) != null
}

class InMemorySkillStore : SkillStore {
    private val items = LinkedHashMap<String, Skill>()

    @Synchronized override fun save(skill: Skill) {
        items[skill.id] = skill
    }

    @Synchronized override fun get(id: String): Skill? = items[id]

    @Synchronized override fun list(): List<Skill> = items.values.toList()

    @Synchronized override fun delete(id: String): Boolean = items.remove(id) != null
}
