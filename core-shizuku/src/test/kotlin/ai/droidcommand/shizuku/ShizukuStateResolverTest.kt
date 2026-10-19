package ai.droidcommand.shizuku

import kotlin.test.Test
import kotlin.test.assertEquals

class ShizukuStateResolverTest {
    @Test
    fun `not installed when no manager and no binder`() {
        assertEquals(
            ShizukuState.NOT_INSTALLED,
            ShizukuStateResolver.resolve(ShizukuSignals(managerInstalled = false, binderAlive = false, permissionGranted = false)),
        )
    }

    @Test
    fun `not running when manager is installed but binder is dead`() {
        assertEquals(
            ShizukuState.NOT_RUNNING,
            ShizukuStateResolver.resolve(ShizukuSignals(managerInstalled = true, binderAlive = false, permissionGranted = false)),
        )
    }

    @Test
    fun `denied when binder is alive without permission`() {
        assertEquals(
            ShizukuState.DENIED,
            ShizukuStateResolver.resolve(ShizukuSignals(managerInstalled = true, binderAlive = true, permissionGranted = false)),
        )
    }

    @Test
    fun `granted when binder is alive and permission is granted`() {
        assertEquals(
            ShizukuState.GRANTED,
            ShizukuStateResolver.resolve(ShizukuSignals(managerInstalled = true, binderAlive = true, permissionGranted = true)),
        )
    }

    @Test
    fun `Sui with a live binder and no visible manager is still present`() {
        assertEquals(
            ShizukuState.DENIED,
            ShizukuStateResolver.resolve(ShizukuSignals(managerInstalled = false, binderAlive = true, permissionGranted = false)),
        )
        assertEquals(
            ShizukuState.GRANTED,
            ShizukuStateResolver.resolve(ShizukuSignals(managerInstalled = false, binderAlive = true, permissionGranted = true)),
        )
    }

    @Test
    fun `permission granted is ignored when the binder is dead`() {
        assertEquals(
            ShizukuState.NOT_RUNNING,
            ShizukuStateResolver.resolve(ShizukuSignals(managerInstalled = true, binderAlive = false, permissionGranted = true)),
        )
        assertEquals(
            ShizukuState.NOT_INSTALLED,
            ShizukuStateResolver.resolve(ShizukuSignals(managerInstalled = false, binderAlive = false, permissionGranted = true)),
        )
    }

    @Test
    fun `copy covers every state and the request-permission prompt`() {
        ShizukuState.entries.forEach { state ->
            assertEquals(false, ShizukuCopy.statusTitle(state).isBlank())
            assertEquals(false, ShizukuCopy.statusBody(state).isBlank())
        }
        assertEquals(true, ShizukuCopy.statusBody(ShizukuState.DENIED).contains("Grant this app permission"))
        assertEquals(true, ShizukuCopy.statusBody(ShizukuState.NOT_RUNNING).contains("wireless debugging"))
        assertEquals(true, ShizukuCopy.statusBody(ShizukuState.NOT_INSTALLED).contains("still works"))
        assertEquals(true, ShizukuCopy.statusBody(ShizukuState.GRANTED).contains("Kali"))
    }
}
