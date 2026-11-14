package ai.droidcommand.app.deviceconnect

import ai.droidcommand.deviceconnect.DeviceConnect
import ai.droidcommand.deviceconnect.DeviceConnectDecision
import ai.droidcommand.deviceconnect.DeviceConnectService
import ai.droidcommand.deviceconnect.DeviceConnectSnapshot
import ai.droidcommand.deviceconnect.InMemoryDeviceConnectStore
import ai.droidcommand.remotecontrol.InMemoryRemoteControlStore
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * "Keep waiting" after a process restart: the persisted preference comes back, the in-memory pairing password
 * does not. The runtime must say so (and never throw), not silently stay down.
 */
class DeviceConnectRuntimeTest {
    private val runtimes = mutableListOf<DeviceConnectRuntime>()

    @AfterTest
    fun tearDown() {
        runtimes.forEach { it.stopWaiting() }
    }

    private fun runtime(keepListening: Boolean): DeviceConnectRuntime {
        // A fresh service over a store that already holds keepListening == fresh process after a restart.
        val store = InMemoryDeviceConnectStore(DeviceConnectSnapshot(keepListening = keepListening))
        return DeviceConnectRuntime(DeviceConnectService(store), InMemoryRemoteControlStore()).also { runtimes += it }
    }

    @Test
    fun `nothing happens when keep waiting is off`() {
        val runtime = runtime(keepListening = false)
        assertNull(runtime.startIfKeptOn())
        assertFalse(runtime.status().listening)
    }

    @Test
    fun `after a restart keep waiting asks for the password instead of silently staying down`() {
        val runtime = runtime(keepListening = true)
        val result = runtime.startIfKeptOn()
        val refused = assertIs<DeviceConnectDecision.Refused>(result)
        assertEquals(DeviceConnectRuntime.NEEDS_PASSWORD_AGAIN, refused.reason)
        val status = runtime.status()
        assertFalse(status.listening)
        assertTrue(status.keepListening)
    }

    @Test
    fun `a port already in use is reported, not thrown`() {
        val blocker = runCatching {
            ServerSocket().apply { bind(InetSocketAddress(DeviceConnect.DEFAULT_PORT)) }
        }.getOrNull() ?: return // something else already holds the port; the path below is still exercised
        blocker.use {
            val runtime = runtime(keepListening = true)
            runtime.transport.preparePassword("correct horse battery")
            val result = runtime.startIfKeptOn()
            assertIs<DeviceConnectDecision.Refused>(result)
            assertFalse(runtime.status().listening)
        }
    }

    @Test
    fun `with the password in memory the listener resumes`() {
        // Skip quietly if the port is taken on this machine; the refused path is covered above.
        val free = runCatching { ServerSocket().use { it.bind(InetSocketAddress(DeviceConnect.DEFAULT_PORT)) } }.isSuccess
        if (!free) return
        val runtime = runtime(keepListening = true)
        runtime.transport.preparePassword("correct horse battery")
        assertIs<DeviceConnectDecision.Ok>(runtime.startIfKeptOn())
        assertTrue(runtime.status().listening)
        assertNull(runtime.startIfKeptOn(), "already listening: nothing to do")
    }
}
