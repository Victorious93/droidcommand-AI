package ai.droidcommand.remotepc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommandConfirmFlowTest {
    private val pc = profile()
    private val safe = SavedPcCommand("1", "Disk space", "df -h")
    private val risky = SavedPcCommand("2", "Restart", "sudo reboot")

    @Test
    fun `a safe command needs exactly one confirm`() {
        val flow = CommandConfirmFlow()
        assertIs<CommandConfirmState.FirstConfirm>(flow.start(pc, safe))
        assertEquals(CommandConfirmState.Approved(pc, safe), flow.confirm())
    }

    @Test
    fun `a risky command needs two confirms`() {
        val flow = CommandConfirmFlow()
        val first = assertIs<CommandConfirmState.FirstConfirm>(flow.start(pc, risky))
        assertTrue(first.risk.risky)
        assertIs<CommandConfirmState.SecondConfirm>(flow.confirm())
        assertEquals(CommandConfirmState.Approved(pc, risky), flow.confirm())
    }

    @Test
    fun `cancel at either step approves nothing`() {
        val flow = CommandConfirmFlow()
        flow.start(pc, risky)
        assertEquals(CommandConfirmState.Idle, flow.cancel())
        assertNull(flow.consume())

        flow.start(pc, risky)
        flow.confirm()
        assertEquals(CommandConfirmState.Idle, flow.cancel())
        assertNull(flow.consume())
    }

    @Test
    fun `confirm with nothing showing does nothing`() {
        val flow = CommandConfirmFlow()
        assertEquals(CommandConfirmState.Idle, flow.confirm())
        assertNull(flow.consume())
    }

    @Test
    fun `approval is consumed once`() {
        val flow = CommandConfirmFlow()
        flow.start(pc, safe)
        flow.confirm()
        assertEquals(CommandConfirmState.Approved(pc, safe), flow.consume())
        assertNull(flow.consume())
        assertEquals(CommandConfirmState.Idle, flow.state)
    }

    @Test
    fun `extra taps after approval cannot re-approve`() {
        val flow = CommandConfirmFlow()
        flow.start(pc, safe)
        flow.confirm()
        assertIs<CommandConfirmState.Approved>(flow.confirm())
        flow.consume()
        assertEquals(CommandConfirmState.Idle, flow.confirm())
    }

    @Test
    fun `starting a new command replaces a pending one`() {
        val flow = CommandConfirmFlow()
        flow.start(pc, risky)
        flow.confirm()
        val again = assertIs<CommandConfirmState.FirstConfirm>(flow.start(pc, safe))
        assertEquals(safe, again.command)
    }
}
