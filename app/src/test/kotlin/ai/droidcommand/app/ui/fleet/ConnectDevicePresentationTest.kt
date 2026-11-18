package ai.droidcommand.app.ui.fleet

import ai.droidcommand.app.ui.components.PillTone
import ai.droidcommand.deviceconnect.DeviceConnect
import ai.droidcommand.deviceconnect.DeviceConnectPhase
import ai.droidcommand.deviceconnect.DeviceKind
import ai.droidcommand.deviceconnect.DeviceStatus
import ai.droidcommand.deviceconnect.TrustedDevice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConnectDevicePresentationTest {
    private val min = DeviceConnect.MIN_PASSWORD_CHARS
    private val good = "x".repeat(min)

    private fun status(phase: DeviceConnectPhase, peers: List<TrustedDevice> = emptyList()) = DeviceStatus(
        phase = phase,
        listening = phase == DeviceConnectPhase.LISTENING,
        keepListening = false,
        localAddresses = emptyList(),
        port = null,
        deviceId = null,
        invite = null,
        verificationCode = null,
        peerName = null,
        peers = peers,
    )

    private fun peer(id: String) = TrustedDevice(deviceId = id, displayName = id, kind = DeviceKind.PC, publicHex = "00", linkedAtMs = 0L)

    @Test
    fun `an empty password is not nagged about`() {
        assertNull(ConnectDevicePresentation.passwordProblem(""))
    }

    @Test
    fun `a short password says how far it is from long enough`() {
        val problem = ConnectDevicePresentation.passwordProblem("abc")
        assertNotNull(problem)
        assertTrue(problem.contains("$min"))
        assertTrue(problem.contains("3 so far"))
    }

    @Test
    fun `the boundary length is accepted and one under is not`() {
        assertNull(ConnectDevicePresentation.passwordProblem(good))
        assertNotNull(ConnectDevicePresentation.passwordProblem(good.dropLast(1)))
        assertTrue(ConnectDevicePresentation.passwordReady(good))
        assertFalse(ConnectDevicePresentation.passwordReady(good.dropLast(1)))
    }

    @Test
    fun `waiting needs only a good password`() {
        assertTrue(ConnectDevicePresentation.canWait(good))
        assertFalse(ConnectDevicePresentation.canWait("short"))
        assertNull(ConnectDevicePresentation.waitHint(good))
        assertNotNull(ConnectDevicePresentation.waitHint("short"))
    }

    @Test
    fun `joining needs a good password and an address`() {
        assertTrue(ConnectDevicePresentation.canJoin(good, "192.168.1.8:17890"))
        assertFalse(ConnectDevicePresentation.canJoin("short", "192.168.1.8:17890"))
        assertFalse(ConnectDevicePresentation.canJoin(good, ""))
        assertFalse(ConnectDevicePresentation.canJoin(good, "   "))
    }

    @Test
    fun `the join hint names the password before the address`() {
        assertTrue(ConnectDevicePresentation.joinHint("short", "")!!.contains("password"))
        assertTrue(ConnectDevicePresentation.joinHint(good, "")!!.contains("address"))
        assertNull(ConnectDevicePresentation.joinHint(good, "192.168.1.8:17890"))
    }

    @Test
    fun `every phase has a status line, and only trouble is shown as an error`() {
        for (phase in DeviceConnectPhase.entries) {
            val (text, tone) = ConnectDevicePresentation.statusLine(status(phase))
            assertTrue(text.isNotBlank(), "phase $phase has no words")
            val isTrouble = phase == DeviceConnectPhase.LOCKED || phase == DeviceConnectPhase.FAILED
            assertEquals(isTrouble, tone == PillTone.Error, "phase $phase has the wrong tone")
        }
    }

    @Test
    fun `an idle phone with paired devices says how many`() {
        assertEquals("Not connected", ConnectDevicePresentation.statusLine(status(DeviceConnectPhase.IDLE)).first)
        assertEquals("Not connected · 1 paired", ConnectDevicePresentation.statusLine(status(DeviceConnectPhase.IDLE, listOf(peer("a")))).first)
        assertEquals("Not connected · 2 paired", ConnectDevicePresentation.statusLine(status(DeviceConnectPhase.IDLE, listOf(peer("a"), peer("b")))).first)
    }
}
