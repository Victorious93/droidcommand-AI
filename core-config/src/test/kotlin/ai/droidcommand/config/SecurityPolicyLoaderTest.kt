package ai.droidcommand.config

import ai.droidcommand.agent.SecurityLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class SecurityPolicyLoaderTest {
    @Test
    fun `defaults to root disabled, no permissions, and only NORMAL auto-approved`() {
        val policy = SecurityPolicyLoader.load(MapConfigSource(emptyMap()))
        assertFalse(policy.rootEnabled)
        assertEquals(emptySet(), policy.grantedPermissions)
        assertEquals(setOf(SecurityLevel.NORMAL), policy.autoApprove)
    }

    @Test
    fun `parses DROIDCOMMAND_ROOT_ENABLED`() {
        val policy = SecurityPolicyLoader.load(MapConfigSource(mapOf("DROIDCOMMAND_ROOT_ENABLED" to "true")))
        assertEquals(true, policy.rootEnabled)
    }

    @Test
    fun `parses a comma-separated, trimmed permission list`() {
        val policy = SecurityPolicyLoader.load(
            MapConfigSource(mapOf("DROIDCOMMAND_GRANTED_PERMISSIONS" to "CAMERA, MICROPHONE ,STORAGE")),
        )
        assertEquals(setOf("CAMERA", "MICROPHONE", "STORAGE"), policy.grantedPermissions)
    }

    @Test
    fun `parses a comma-separated, case-insensitive auto-approve level list`() {
        val policy = SecurityPolicyLoader.load(
            MapConfigSource(mapOf("DROIDCOMMAND_AUTO_APPROVE_LEVELS" to "normal, sensitive")),
        )
        assertEquals(setOf(SecurityLevel.NORMAL, SecurityLevel.SENSITIVE), policy.autoApprove)
    }

    @Test
    fun `an unrecognized security level name fails fast instead of being silently ignored`() {
        assertFailsWith<IllegalArgumentException> {
            SecurityPolicyLoader.load(MapConfigSource(mapOf("DROIDCOMMAND_AUTO_APPROVE_LEVELS" to "not-a-real-level")))
        }
    }

    @Test
    fun `rootAvailable is passed through unchanged, never read from configuration`() {
        var calls = 0
        val rootAvailable = {
            calls++
            true
        }
        val policy = SecurityPolicyLoader.load(MapConfigSource(emptyMap()), rootAvailable = rootAvailable)

        assertEquals(0, calls) // not called during loading itself
        assertEquals(true, policy.rootAvailable())
        assertEquals(1, calls)
    }
}
