package ai.droidcommand.hackerai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SubagentSkill(
    val id: String,
    val category: String,
    val filename: String,
    val name: String,
    val description: String,
    val contentBytes: Int,
    val sourcePath: String,
    val sourceSha256: String,
    val internal: Boolean = false,
)

@Serializable
private data class SkillRegistry(
    val sourceRepository: String,
    val sourceCommit: String,
    val skills: List<SubagentSkill>,
)

sealed class ResolveSkillsResult {
    data class Success(val skills: List<SubagentSkill>) : ResolveSkillsResult()
    data class Failure(val error: String) : ResolveSkillsResult()
}

object SkillCatalog {
    private val json = Json { ignoreUnknownKeys = true }

    private val registry: SkillRegistry by lazy {
        val resource = SkillCatalog::class.java
            .getResourceAsStream("/ai/droidcommand/hackerai/strix-skill-catalog.generated.json")
            ?: error("strix-skill-catalog.generated.json not found on classpath")
        json.decodeFromString(SkillRegistry.serializer(), resource.bufferedReader().readText())
    }

    val sourceCommit: String get() = registry.sourceCommit

    fun listSkills(): List<SubagentSkill> =
        registry.skills.filter { !it.internal }.sortedBy { it.id }

    fun resolveSkills(ids: List<String>): ResolveSkillsResult {
        val available = listSkills().associateBy { it.id }
        val resolved = mutableListOf<SubagentSkill>()
        for (id in ids) {
            val skill = available[id]
                ?: return ResolveSkillsResult.Failure("Unknown skill id: $id")
            resolved += skill
        }
        return ResolveSkillsResult.Success(resolved)
    }
}
