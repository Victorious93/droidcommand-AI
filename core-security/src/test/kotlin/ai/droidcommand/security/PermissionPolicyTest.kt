package ai.droidcommand.security

import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.ToolSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PermissionCategoryTest {
    @Test
    fun `exactly ROOT and CONTAINER are root-equivalent`() {
        val rootEquivalent = PermissionCategory.entries.filter { it.isRootEquivalent }.toSet()
        assertEquals(setOf(PermissionCategory.ROOT, PermissionCategory.CONTAINER), rootEquivalent)
    }

    @Test
    fun `every non-ROOT, non-CONTAINER category is not root-equivalent`() {
        val ordinary = PermissionCategory.entries - PermissionCategory.ROOT - PermissionCategory.CONTAINER
        assertTrue(ordinary.isNotEmpty())
        ordinary.forEach { assertFalse(it.isRootEquivalent, "$it should not be root-equivalent") }
    }

    @Test
    fun `all 13 spec categories are present`() {
        assertEquals(13, PermissionCategory.entries.size)
    }
}

class ValidateRootEquivalentPermissionsTest {
    @Test
    fun `a tool requiring CONTAINER at NORMAL is denied, naming CONTAINER`() {
        val spec = ToolSpec(name = "docker-run", description = "d", requiredPermissions = setOf("CONTAINER"))
        val decision = assertIs<PolicyDecision.Deny>(validateRootEquivalentPermissions(spec))
        assertTrue(decision.reason.contains("CONTAINER"))
    }

    @Test
    fun `the same tool declared ROOT is allowed`() {
        val spec = ToolSpec(
            name = "docker-run",
            description = "d",
            requiredPermissions = setOf("CONTAINER"),
            securityLevel = SecurityLevel.ROOT,
        )
        assertEquals(PolicyDecision.Allow, validateRootEquivalentPermissions(spec))
    }

    @Test
    fun `a tool requiring the literal ROOT category at SENSITIVE is denied`() {
        val spec = ToolSpec(
            name = "rm",
            description = "d",
            requiredPermissions = setOf("ROOT"),
            securityLevel = SecurityLevel.SENSITIVE,
        )
        assertIs<PolicyDecision.Deny>(validateRootEquivalentPermissions(spec))
    }

    @Test
    fun `a tool with no root-equivalent permissions at NORMAL is allowed`() {
        val spec = ToolSpec(name = "echo", description = "d")
        assertEquals(PolicyDecision.Allow, validateRootEquivalentPermissions(spec))
    }

    @Test
    fun `a tool requiring a non-root-equivalent category at NORMAL is allowed`() {
        val spec = ToolSpec(name = "fetch", description = "d", requiredPermissions = setOf("NETWORK"))
        assertEquals(PolicyDecision.Allow, validateRootEquivalentPermissions(spec))
    }

    @Test
    fun `a free-form permission string matching no PermissionCategory is silently ignored`() {
        val spec = ToolSpec(name = "record", description = "d", requiredPermissions = setOf("CAMERA", "MICROPHONE"))
        assertEquals(PolicyDecision.Allow, validateRootEquivalentPermissions(spec))
    }
}

class EscalationTierTest {
    @Test
    fun `entries match the spec's exact 7-step order`() {
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
    fun `leastPrivilegedAvailable returns the earliest-declared tier present in a mixed set`() {
        val available = setOf(EscalationTier.ROOT, EscalationTier.SHIZUKU, EscalationTier.ACCESSIBILITY_SERVICE)
        assertEquals(EscalationTier.ACCESSIBILITY_SERVICE, leastPrivilegedAvailable(available))
    }

    @Test
    fun `an empty available set returns null, never a fabricated fallback`() {
        assertNull(leastPrivilegedAvailable(emptySet()))
    }

    @Test
    fun `a set containing only ROOT returns ROOT`() {
        assertEquals(EscalationTier.ROOT, leastPrivilegedAvailable(setOf(EscalationTier.ROOT)))
    }
}
