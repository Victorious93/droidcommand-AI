package ai.droidcommand.hackerai

import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

class SkillCatalogTest {
    @Test
    fun `catalog loads without error`() {
        val skills = SkillCatalog.listSkills()
        assertTrue(skills.isNotEmpty(), "Catalog must contain at least one skill")
    }

    @Test
    fun `no internal skills are exposed in listSkills`() {
        val skills = SkillCatalog.listSkills()
        assertTrue(skills.none { it.internal }, "No internal skills should be in listSkills()")
    }

    @Test
    fun `skills are sorted by id`() {
        val skills = SkillCatalog.listSkills()
        val sorted = skills.sortedBy { it.id }
        assertTrue(skills.zip(sorted).all { (a, b) -> a.id == b.id }, "listSkills() should be sorted by id")
    }

    @Test
    fun `sourceCommit is a non-empty string`() {
        assertTrue(SkillCatalog.sourceCommit.isNotEmpty())
    }

    @Test
    fun `resolveSkills returns success for known id`() {
        val skills = SkillCatalog.listSkills()
        assertTrue(skills.isNotEmpty())
        val firstId = skills.first().id
        val result = SkillCatalog.resolveSkills(listOf(firstId))
        assertIs<ResolveSkillsResult.Success>(result)
        assertTrue((result as ResolveSkillsResult.Success).skills.isNotEmpty())
    }

    @Test
    fun `resolveSkills returns failure for unknown id`() {
        val result = SkillCatalog.resolveSkills(listOf("nonexistent/skill_id"))
        assertIs<ResolveSkillsResult.Failure>(result)
    }

    @Test
    fun `resolveSkills empty list returns empty success`() {
        val result = SkillCatalog.resolveSkills(emptyList())
        assertIs<ResolveSkillsResult.Success>(result)
        assertTrue((result as ResolveSkillsResult.Success).skills.isEmpty())
    }
}
