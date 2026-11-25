package ai.droidcommand.app.ui.communications

import ai.droidcommand.agent.ToolRegistry
import ai.droidcommand.app.communications.DraftRuntime
import ai.droidcommand.communications.ApprovalQueue
import ai.droidcommand.communications.ArchiveAnswer
import ai.droidcommand.communications.ArchiveChannel
import ai.droidcommand.communications.ArchivePoller
import ai.droidcommand.communications.BackendContext
import ai.droidcommand.communications.BackendResult
import ai.droidcommand.communications.CommsAdmin
import ai.droidcommand.communications.CommsBackend
import ai.droidcommand.communications.CommsBroker
import ai.droidcommand.communications.CommunicationsAgentTools
import ai.droidcommand.communications.ConversationContext
import ai.droidcommand.communications.ConversationOrchestrator
import ai.droidcommand.communications.DraftInbox
import ai.droidcommand.communications.FileShareEngine
import ai.droidcommand.communications.InMemoryGrantStore
import ai.droidcommand.communications.InMemoryReceiptStore
import ai.droidcommand.communications.InMemoryShareStore
import ai.droidcommand.communications.KillSwitch
import ai.droidcommand.communications.TurnEngine
import ai.droidcommand.communications.TurnResult
import ai.droidcommand.security.InMemoryAuditLog
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Victorious Framework: the owner's switch on the Communications screen, driven through the real view model. JVM only; no device. */
class CommunicationsViewModelTest {
    private class Backend : CommsBackend {
        override fun availability(capability: String): String? = null

        override fun call(capability: String, args: Map<String, String>, ctx: BackendContext) = BackendResult.Ok("""{"done":true}""")

        override fun context(conversation: String, query: String, maxChars: Int): ConversationContext? = null
    }

    /** A VictorSMS that is never reachable: the draft pipeline is not what these tests are about. */
    private object NoArchive : ArchiveChannel {
        override fun call(operation: String, params: kotlinx.serialization.json.JsonObject) = ArchiveAnswer.Unavailable("test")
    }

    private class Setup {
        var stored = false
        val registry = ToolRegistry()
        val kill = KillSwitch()
        val grants = InMemoryGrantStore()
        val approvals = ApprovalQueue(clock = System::currentTimeMillis)
        val broker = CommsBroker(Backend(), null, grants, approvals, InMemoryReceiptStore(), kill, InMemoryAuditLog())
        val tools = CommunicationsAgentTools(registry, broker, { stored }, persist = { stored = it })
        val inbox = DraftInbox()
        val orchestrator = ConversationOrchestrator(Executors.newSingleThreadExecutor(), 1, TurnEngine { TurnResult(null) }, { _, _ -> })
        val drafts = DraftRuntime(inbox, ArchivePoller(NoArchive, inbox, orchestrator), NoArchive)
        val vm = CommunicationsViewModel(
            CommsAdmin(grants, approvals, kill),
            broker,
            FileShareEngine(InMemoryShareStore(), emergencyStop = { kill.engaged }),
            tools,
            drafts,
        )
    }

    @Test
    fun `a fresh install shows the switch off and offers the agent nothing`() {
        val s = Setup()
        assertFalse(s.vm.ui.value.agentTools)
        assertEquals(0, s.vm.ui.value.agentToolCount)
        assertTrue(s.registry.list().isEmpty())
    }

    @Test
    fun `turning it on saves the choice and offers the five tools`() {
        val s = Setup()
        s.vm.setAgentTools(true)
        assertTrue(s.stored)
        assertTrue(s.vm.ui.value.agentTools)
        assertEquals(5, s.vm.ui.value.agentToolCount)
        assertEquals(5, s.registry.list().size)
        assertTrue(s.vm.ui.value.message.orEmpty().contains("5 communications tools"))
    }

    @Test
    fun `turning it off removes them again and says so`() {
        val s = Setup()
        s.vm.setAgentTools(true)
        s.vm.setAgentTools(false)
        assertFalse(s.stored)
        assertFalse(s.vm.ui.value.agentTools)
        assertEquals(0, s.vm.ui.value.agentToolCount)
        assertTrue(s.registry.list().isEmpty())
        assertEquals("The agent has no communications tools now.", s.vm.ui.value.message)
    }

    @Test
    fun `refreshing the screen never turns the switch on`() {
        val s = Setup()
        s.vm.refresh()
        s.vm.setStopped(true)
        s.vm.setStopped(false)
        assertFalse(s.vm.ui.value.agentTools)
        assertTrue(s.registry.list().isEmpty())
    }
}
