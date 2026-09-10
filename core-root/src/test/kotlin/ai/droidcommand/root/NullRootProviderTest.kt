package ai.droidcommand.root

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NullRootProviderTest {
    private val provider = NullRootProvider()

    @Test
    fun `reports no root available or authorized`() {
        assertFalse(provider.isRootAvailable())
        assertFalse(provider.isAuthorized())
        assertEquals(PrivilegeLevel.NONE, provider.getPrivilegeLevel())
    }

    @Test
    fun `reports no capabilities`() {
        assertTrue(provider.getCapabilities().isEmpty())
    }

    @Test
    fun `health is unavailable with an explicit reason, regardless of probeShell`() {
        for (probeShell in listOf(false, true)) {
            val health = provider.checkHealth(probeShell = probeShell)
            assertEquals(RootProviderState.UNAVAILABLE, health.state)
            assertFalse(health.rootAvailable)
            assertFalse(health.rootAuthorized)
            assertFalse(health.rootShellAvailable)
            assertEquals(PrivilegeLevel.NONE, health.privilegeLevel)
            assertTrue(health.lastError!!.contains("no root provider", ignoreCase = true))
        }
    }

    @Test
    fun `execute fails explicitly rather than fabricating success`() {
        val result = provider.execute(RootCommand(executable = "id"))
        assertIs<RootExecutionResult.Failure>(result)
        assertTrue(result.reason.contains("no root provider", ignoreCase = true))
    }
}
