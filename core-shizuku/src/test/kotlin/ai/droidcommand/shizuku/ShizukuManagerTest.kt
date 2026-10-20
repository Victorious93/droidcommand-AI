package ai.droidcommand.shizuku

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class FakeShizukuHost : ShizukuHost {
    var signals = ShizukuSignals(false, false, false)
    var requestCodes = mutableListOf<Int>()
    var bound: ShizukuHostListener? = null

    override fun signals(): ShizukuSignals = signals

    override fun requestPermission(requestCode: Int) {
        requestCodes += requestCode
    }

    override fun setListener(listener: ShizukuHostListener?) {
        bound = listener
    }
}

class ShizukuManagerTest {
    @Test
    fun `start is idempotent and binder changes notify listeners`() {
        val host = FakeShizukuHost()
        val seen = mutableListOf<ShizukuState>()
        val manager = ShizukuManager(host)
        manager.start()
        manager.start()
        manager.addListener { seen += it }
        assertEquals(listOf(ShizukuState.NOT_INSTALLED), seen)

        host.signals = ShizukuSignals(managerInstalled = true, binderAlive = true, permissionGranted = false)
        host.bound!!.onBinderChanged()
        assertEquals(ShizukuState.DENIED, seen.last())
    }

    @Test
    fun `requestPermission only fires when denied`() {
        val host = FakeShizukuHost()
        val manager = ShizukuManager(host)
        manager.start()

        manager.requestPermission()
        assertTrue(host.requestCodes.isEmpty())

        host.signals = ShizukuSignals(true, true, false)
        manager.requestPermission()
        assertEquals(listOf(ShizukuManager.PERMISSION_REQUEST_CODE), host.requestCodes)

        host.signals = ShizukuSignals(true, true, true)
        manager.requestPermission()
        assertEquals(1, host.requestCodes.size)
    }

    @Test
    fun `permission listener ignores other request codes`() {
        val host = FakeShizukuHost()
        val seen = mutableListOf<ShizukuState>()
        val manager = ShizukuManager(host)
        manager.start()
        manager.addListener { seen += it }
        seen.clear()
        host.bound!!.onPermissionResult(99, true)
        assertTrue(seen.isEmpty())
        host.signals = ShizukuSignals(true, true, true)
        host.bound!!.onPermissionResult(ShizukuManager.PERMISSION_REQUEST_CODE, true)
        assertEquals(listOf(ShizukuState.GRANTED), seen)
    }

    @Test
    fun `stop drops the host listener`() {
        val host = FakeShizukuHost()
        val manager = ShizukuManager(host)
        manager.start()
        manager.stop()
        assertEquals(null, host.bound)
    }
}
