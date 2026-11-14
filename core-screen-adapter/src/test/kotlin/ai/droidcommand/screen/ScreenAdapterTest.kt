package ai.droidcommand.screen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ScreenAdapterTest {
    private val target = PairedTarget("tablet-1", "Tablet", "ab".repeat(32), 1L)
    private var settings = ScreenAdapterSettings()
    private fun session() = ScreenSession({ settings }, target)
    private val on = ScreenAdapterSettings().enableAfterOwnerConfirmedDisclosure().grantView().grantInput()

    @Test fun `everything is off by default and grants need the switch first`() {
        val d = ScreenAdapterSettings()
        assertFalse(d.enabled || d.viewGranted || d.inputGranted)
        assertEquals(d, d.grantView().grantInput()) // cannot grant while disabled
        assertIs<ScreenDecision.Denied>(ScreenAdapterPolicy.decide(d, target, ScreenCapability.VIEW))
        assertFalse(d.clipboardSync || d.changeTargetResolution || d.changeTargetKeepAwake || d.grantShellRootFilesOrSettings)
    }

    @Test fun `view and input are separate grants and input needs view`() {
        val viewOnly = ScreenAdapterSettings().enableAfterOwnerConfirmedDisclosure().grantView()
        assertEquals(ScreenDecision.Allowed, ScreenAdapterPolicy.decide(viewOnly, target, ScreenCapability.VIEW))
        assertIs<ScreenDecision.Denied>(ScreenAdapterPolicy.decide(viewOnly, target, ScreenCapability.INPUT))
        assertEquals(viewOnly, ScreenAdapterSettings().enableAfterOwnerConfirmedDisclosure().grantInput().let { viewOnly }) // input before view is a no-op
        assertEquals(ScreenAdapterSettings(true, false, false), on.revokeView()) // revoking view drops input too
        assertEquals(ScreenAdapterSettings(), on.disable())
        assertIs<ScreenDecision.Denied>(ScreenAdapterPolicy.decide(on, null, ScreenCapability.VIEW))
    }

    @Test fun `disclosure states that app switches do not restrict the adb key`() {
        assertTrue(AdbDisclosure.TEXT.contains("do not limit the key"))
        assertTrue(AdbDisclosure.TEXT.contains("shell"))
    }

    @Test fun `stream limits come from what the target reported and never change the target`() {
        val caps = TargetCapabilities(1080, 2400, 34, h264 = true, h265 = false, refreshRateHz = 90)
        val ok = StreamLimits.bound(StreamRequest(4000, 120, 50_000_000, preferH265 = true), caps) as StreamLimits.Result.Ok
        assertEquals(StreamSettings(2400, 60, 12_000_000, "h264"), ok.s)
        assertIs<StreamLimits.Result.Refused>(StreamLimits.bound(StreamRequest(1280, 30, 4_000_000), caps.copy(androidSdk = 29)))
        assertIs<StreamLimits.Result.Refused>(StreamLimits.bound(StreamRequest(1280, 30, 4_000_000), caps.copy(h264 = false)))
        assertIs<StreamLimits.Result.Refused>(StreamLimits.bound(StreamRequest(100, 30, 4_000_000), caps))
        assertEquals(30, (StreamLimits.bound(StreamRequest(1280, 60, 4_000_000), caps.copy(refreshRateHz = 30)) as StreamLimits.Result.Ok).s.maxFps)
    }

    @Test fun `target identity is validated`() {
        assertFailsWith<IllegalArgumentException> { PairedTarget("a b", "x", "ab".repeat(32), 1) }
        assertFailsWith<IllegalArgumentException> { PairedTarget("ok", "x", "XYZ", 1) }
    }

    @Test fun `a session cannot start when disabled or when the shared foreground gate is taken`() {
        val s = session()
        assertIs<ScreenDecision.Denied>(s.start(foregroundAcquired = true))
        assertEquals(SessionState.Idle, s.state)
        settings = on
        assertIs<ScreenDecision.Denied>(s.start(foregroundAcquired = false))
        assertEquals(SessionState.Idle, s.state)
        assertEquals(ScreenDecision.Allowed, s.start(true))
        assertEquals(SessionState.Connecting, s.state)
        assertIs<ScreenDecision.Denied>(s.start(true)) // no second session
    }

    @Test fun `every end reason demands full cleanup and the session is not Closed until all of it is acknowledged`() {
        for (r in EndReason.values()) {
            settings = on
            val s = session()
            s.start(true)
            s.connected()
            val todo = s.end(r)
            assertEquals(Cleanup.values().toSet(), todo)
            assertIs<SessionState.Stopping>(s.state)
            todo.take(todo.size - 1).forEach { s.cleanupDone(it) }
            assertIs<SessionState.Stopping>(s.state, "still stopping for $r with one step outstanding")
            s.cleanupDone(todo.last())
            assertEquals(SessionState.Closed(r), s.state)
            assertEquals(emptySet(), s.end(EndReason.CANCELLED)) // idempotent after close
        }
    }

    @Test fun `revoking input mid-stream stops injection at once and revoking view ends the session`() {
        settings = on
        val s = session()
        s.start(true)
        s.connected()
        assertEquals(ScreenDecision.Allowed, s.mayInject())
        settings = on.revokeInput()
        assertIs<ScreenDecision.Denied>(s.mayInject())
        assertEquals(emptySet(), s.policyChanged())
        assertEquals(SessionState.Streaming, s.state)
        settings = on.revokeView()
        assertEquals(Cleanup.values().toSet(), s.policyChanged())
        assertEquals(EndReason.POLICY_CHANGED, (s.state as SessionState.Stopping).reason)
    }

    @Test fun `rotation is not an end event and errors are reported as errors with cleanup owed`() {
        settings = on
        val s = session()
        s.start(true)
        s.connected()
        s.rotated()
        assertEquals(SessionState.Streaming, s.state)
        val owed = s.fail("decoder crashed")
        assertEquals(Cleanup.values().toSet(), owed)
        assertIs<SessionState.Failed>(s.state)
        assertIs<ScreenDecision.Denied>(s.mayInject())
    }
}
