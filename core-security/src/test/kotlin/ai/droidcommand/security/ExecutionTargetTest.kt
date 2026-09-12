package ai.droidcommand.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class FakeExecutionTarget(
    override val id: String = "fake-1",
    override val type: ExecutionTargetType = ExecutionTargetType.ANDROID,
    override val context: ExecutionContext = ExecutionContext(
        workingDir = "/",
        user = "user",
        environment = emptyMap(),
        privilegeLevel = PrivilegeLevel.USER,
    ),
    override val availableCapabilities: Set<CapabilityId> = emptySet(),
    private val healthy: Boolean = true,
    private val result: ExecutionResult = ExecutionResult(0, "", "", false, type, false),
) : ExecutionTarget {
    var executeCallCount = 0
        private set

    override fun isHealthy(): Boolean = healthy

    override fun execute(argv: List<String>, workingDir: String?, env: Map<String, String>?, timeoutMs: Long): ExecutionResult {
        executeCallCount++
        return result
    }
}

class ExecutionContextTest {
    @Test
    fun `carries working directory, user, uid, environment and privilege level`() {
        val context = ExecutionContext(
            workingDir = "/home/user",
            user = "user",
            uid = 1000,
            environment = mapOf("PATH" to "/usr/bin"),
            privilegeLevel = PrivilegeLevel.ELEVATED,
        )
        assertEquals("/home/user", context.workingDir)
        assertEquals("user", context.user)
        assertEquals(1000, context.uid)
        assertEquals(mapOf("PATH" to "/usr/bin"), context.environment)
        assertEquals(PrivilegeLevel.ELEVATED, context.privilegeLevel)
    }

    @Test
    fun `uid defaults to null`() {
        val context = ExecutionContext(workingDir = "/", user = "root", environment = emptyMap(), privilegeLevel = PrivilegeLevel.ROOT)
        assertEquals(null, context.uid)
    }
}

class ExecutionResultTest {
    @Test
    fun `carries exit code, stdout, stderr, timedOut, target and verified`() {
        val result = ExecutionResult(
            exitCode = 1,
            stdout = "out",
            stderr = "err",
            timedOut = true,
            target = ExecutionTargetType.DOCKER,
            verified = false,
        )
        assertEquals(1, result.exitCode)
        assertEquals("out", result.stdout)
        assertEquals("err", result.stderr)
        assertTrue(result.timedOut)
        assertEquals(ExecutionTargetType.DOCKER, result.target)
        assertFalse(result.verified)
    }
}

class ExecutionTargetTest {
    @Test
    fun `an implementation exposes id, type, context and availableCapabilities`() {
        val caps = setOf(CapabilityId("android.notifications.read"))
        val target = FakeExecutionTarget(id = "android-1", availableCapabilities = caps)
        assertEquals("android-1", target.id)
        assertEquals(ExecutionTargetType.ANDROID, target.type)
        assertEquals(caps, target.availableCapabilities)
    }

    @Test
    fun `isHealthy and execute are callable`() {
        val target = FakeExecutionTarget(healthy = false)
        assertFalse(target.isHealthy())
        target.execute(listOf("noop"))
        assertEquals(1, target.executeCallCount)
    }
}
