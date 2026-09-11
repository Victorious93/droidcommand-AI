package ai.droidcommand.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PermissionPolicyTest {
    @Test
    fun `PrivilegeEscalationTier declaration order matches the roadmap's escalation preference order`() {
        assertEquals(
            listOf(
                "ANDROID_SYSTEM_API",
                "ACCESSIBILITY_SERVICE",
                "ANDROID_RUNTIME_PERMISSION",
                "TERMUX",
                "SHIZUKU",
                "ADB",
                "ROOT",
            ),
            PrivilegeEscalationTier.entries.map { it.name },
        )
    }

    @Test
    fun `leastEscalatedAvailable of an empty set is null`() {
        assertNull(leastEscalatedAvailable(emptySet()))
    }

    @Test
    fun `leastEscalatedAvailable of a single tier is that tier`() {
        assertEquals(PrivilegeEscalationTier.SHIZUKU, leastEscalatedAvailable(setOf(PrivilegeEscalationTier.SHIZUKU)))
    }

    @Test
    fun `leastEscalatedAvailable picks the lowest-ordinal tier regardless of input order`() {
        val tiers = setOf(PrivilegeEscalationTier.ROOT, PrivilegeEscalationTier.ANDROID_RUNTIME_PERMISSION, PrivilegeEscalationTier.ADB)

        assertEquals(PrivilegeEscalationTier.ANDROID_RUNTIME_PERMISSION, leastEscalatedAvailable(tiers))
    }

    @Test
    fun `leastEscalatedAvailable never prefers ROOT when a less-escalated tier is available`() {
        assertEquals(
            PrivilegeEscalationTier.ANDROID_SYSTEM_API,
            leastEscalatedAvailable(setOf(PrivilegeEscalationTier.ROOT, PrivilegeEscalationTier.ANDROID_SYSTEM_API)),
        )
    }
}
