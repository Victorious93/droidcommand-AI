package ai.droidcommand.security

import ai.droidcommand.agent.PermissionCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecurityPolicyIsCategoryGrantedTest {
    @Test
    fun `a non-root-equivalent category granted in grantedCategories is granted`() {
        val policy = SecurityPolicy(grantedCategories = setOf(PermissionCategory.VIEW))
        assertTrue(policy.isCategoryGranted(PermissionCategory.VIEW))
    }

    @Test
    fun `a non-root-equivalent category not in grantedCategories is not granted`() {
        val policy = SecurityPolicy(grantedCategories = emptySet())
        assertFalse(policy.isCategoryGranted(PermissionCategory.VIEW))
    }

    @Test
    fun `CONTAINER listed in grantedCategories is still denied when root is disabled`() {
        val policy = SecurityPolicy(
            rootEnabled = false,
            grantedCategories = setOf(PermissionCategory.CONTAINER),
        )
        assertFalse(policy.isCategoryGranted(PermissionCategory.CONTAINER))
    }

    @Test
    fun `CONTAINER listed in grantedCategories is still denied when root is enabled but unavailable`() {
        val policy = SecurityPolicy(
            rootEnabled = true,
            rootAvailable = { false },
            grantedCategories = setOf(PermissionCategory.CONTAINER),
        )
        assertFalse(policy.isCategoryGranted(PermissionCategory.CONTAINER))
    }

    @Test
    fun `CONTAINER is granted only when listed and root is enabled and available`() {
        val policy = SecurityPolicy(
            rootEnabled = true,
            rootAvailable = { true },
            grantedCategories = setOf(PermissionCategory.CONTAINER),
        )
        assertTrue(policy.isCategoryGranted(PermissionCategory.CONTAINER))
    }

    @Test
    fun `ROOT itself follows the same root-equivalent gate`() {
        val notEnabled = SecurityPolicy(rootEnabled = false, grantedCategories = setOf(PermissionCategory.ROOT))
        assertFalse(notEnabled.isCategoryGranted(PermissionCategory.ROOT))

        val enabledAndAvailable = SecurityPolicy(
            rootEnabled = true,
            rootAvailable = { true },
            grantedCategories = setOf(PermissionCategory.ROOT),
        )
        assertTrue(enabledAndAvailable.isCategoryGranted(PermissionCategory.ROOT))
    }

    @Test
    fun `a root-equivalent category satisfying the root gate but absent from grantedCategories is still not granted`() {
        val policy = SecurityPolicy(rootEnabled = true, rootAvailable = { true }, grantedCategories = emptySet())
        assertFalse(policy.isCategoryGranted(PermissionCategory.CONTAINER))
    }
}

class EscalationTierTest {
    @Test
    fun `all 7 tiers from the roadmap prompt are present in preference order`() {
        assertEquals(
            listOf(
                EscalationTier.ANDROID_SYSTEM_API,
                EscalationTier.ACCESSIBILITY_SERVICE,
                EscalationTier.ANDROID_RUNTIME_PERMISSION,
                EscalationTier.TERMUX,
                EscalationTier.SHIZUKU,
                EscalationTier.ADB,
                EscalationTier.ROOT,
            ),
            EscalationTier.entries,
        )
    }

    @Test
    fun `ROOT is the last-resort tier`() {
        assertEquals(EscalationTier.entries.last(), EscalationTier.ROOT)
    }

    @Test
    fun `TERMUX precedes SHIZUKU in the preference order`() {
        assertTrue(EscalationTier.TERMUX.ordinal < EscalationTier.SHIZUKU.ordinal)
    }
}
