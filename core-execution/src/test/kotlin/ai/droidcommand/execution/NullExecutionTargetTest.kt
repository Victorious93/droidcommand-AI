package ai.droidcommand.execution

import ai.droidcommand.agent.ExecutionTargetType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun context() = ExecutionContext(
    workingDir = "/tmp",
    user = "test",
    environment = emptyMap(),
    privilegeLevel = PrivilegeLevel.USER,
)

class NullExecutionTargetTest {
    @Test
    fun `is never healthy, for every target type`() {
        ExecutionTargetType.entries.forEach { type ->
            val t = NullExecutionTarget(id = "stub", type = type, context = context())
            assertFalse(t.isHealthy(), "$type should not report healthy")
        }
    }

    @Test
    fun `execute never throws and reports failure naming the target type, for ANDROID`() {
        val t = NullExecutionTarget(id = "android-stub", type = ExecutionTargetType.ANDROID, context = context())
        val result = t.execute(listOf("whoami"))

        assertEquals(-1, result.exitCode)
        assertEquals("", result.stdout)
        assertTrue(result.stderr.contains("android-stub"))
        assertTrue(result.stderr.contains("ANDROID"))
        assertEquals(ExecutionTargetType.ANDROID, result.target)
        assertFalse(result.timedOut)
        assertFalse(result.verified)
    }

    @Test
    fun `execute never throws and reports failure naming the target type, for PROXMOX_VM`() {
        val t = NullExecutionTarget(id = "pve-stub", type = ExecutionTargetType.PROXMOX_VM, context = context())
        val result = t.execute(listOf("qm", "list"))

        assertEquals(-1, result.exitCode)
        assertTrue(result.stderr.contains("pve-stub"))
        assertTrue(result.stderr.contains("PROXMOX_VM"))
        assertEquals(ExecutionTargetType.PROXMOX_VM, result.target)
    }

    @Test
    fun `availableCapabilities defaults to empty`() {
        val t = NullExecutionTarget(id = "stub", type = ExecutionTargetType.DOCKER, context = context())
        assertEquals(emptySet(), t.availableCapabilities)
    }
}
