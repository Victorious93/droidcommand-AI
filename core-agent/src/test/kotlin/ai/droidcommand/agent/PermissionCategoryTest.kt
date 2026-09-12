package ai.droidcommand.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PermissionCategoryTest {
    @Test
    fun `all 13 categories from the roadmap prompt are present`() {
        assertEquals(13, PermissionCategory.entries.size)
    }

    @Test
    fun `ROOT and CONTAINER are the only root-equivalent categories`() {
        assertEquals(setOf(PermissionCategory.ROOT, PermissionCategory.CONTAINER), ROOT_EQUIVALENT_CATEGORIES)
    }

    @Test
    fun `CONTAINER is never treated as a peer of VIEW or AUTOMATION`() {
        assertFalse(PermissionCategory.VIEW in ROOT_EQUIVALENT_CATEGORIES)
        assertFalse(PermissionCategory.AUTOMATION in ROOT_EQUIVALENT_CATEGORIES)
    }
}
