package ai.droidcommand.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class FakeScheduler : Scheduler {
    data class Scheduled(val initialDelayMs: Long, val periodMs: Long, val task: () -> Unit)

    val scheduled = mutableListOf<Scheduled>()
    var cancelCount = 0

    override fun scheduleAtFixedRate(initialDelayMs: Long, periodMs: Long, task: () -> Unit): Cancellable {
        val entry = Scheduled(initialDelayMs, periodMs, task)
        scheduled += entry
        return Cancellable { cancelCount++ }
    }
}

private class ScheduledRecordingTool : Tool {
    override val spec = ToolSpec(name = "recording", description = "records invocations")
    var invocations = 0
        private set

    override fun execute(input: Map<String, String>): ToolResult {
        invocations++
        return ToolResult.Success("ran")
    }
}

private class SchedulerRecordingLogger : Logger {
    val events = mutableListOf<LogEvent>()
    override fun log(event: LogEvent) {
        events += event
    }
}

class MacroSchedulerTest {
    private fun newSetup(logger: Logger = NoOpLogger): Triple<MacroScheduler, MacroStore, ScheduledRecordingTool> {
        val tool = ScheduledRecordingTool()
        val registry = ToolRegistry().apply { register(tool) }
        val toolExecutor = ToolExecutor(registry, AgentStateMachine(), sleep = { })
        val store = InMemoryMacroStore()
        val macroExecutor = MacroExecutor(toolExecutor)
        val fakeScheduler = FakeScheduler()
        val scheduler = MacroScheduler(store, macroExecutor, fakeScheduler, logger)
        return Triple(scheduler, store, tool)
    }

    @Test
    fun `scheduleRecurring registers a schedule and returns true`() {
        val (scheduler, store, _) = newSetup()
        store.save(Macro("greet", emptyList()))

        assertTrue(scheduler.scheduleRecurring("greet", periodMs = 1000))
        assertTrue(scheduler.isScheduled("greet"))
    }

    @Test
    fun `scheduleRecurring returns false and does not double-schedule when already scheduled`() {
        val tool = ScheduledRecordingTool()
        val registry = ToolRegistry().apply { register(tool) }
        val toolExecutor = ToolExecutor(registry, AgentStateMachine(), sleep = { })
        val store = InMemoryMacroStore().apply { save(Macro("greet", emptyList())) }
        val fakeScheduler = FakeScheduler()
        val scheduler = MacroScheduler(store, MacroExecutor(toolExecutor), fakeScheduler)

        assertTrue(scheduler.scheduleRecurring("greet", periodMs = 1000))
        assertFalse(scheduler.scheduleRecurring("greet", periodMs = 5000))
        assertEquals(1, fakeScheduler.scheduled.size)
    }

    @Test
    fun `cancel stops tracking and cancels the underlying schedule`() {
        val (scheduler, store, _) = newSetup()
        store.save(Macro("greet", emptyList()))
        scheduler.scheduleRecurring("greet", periodMs = 1000)

        assertTrue(scheduler.cancel("greet"))
        assertFalse(scheduler.isScheduled("greet"))
    }

    @Test
    fun `cancel of a name that was never scheduled returns false`() {
        val (scheduler, _, _) = newSetup()
        assertFalse(scheduler.cancel("nope"))
    }

    @Test
    fun `firing the scheduled task loads the macro fresh and runs it`() {
        val tool = ScheduledRecordingTool()
        val registry = ToolRegistry().apply { register(tool) }
        val toolExecutor = ToolExecutor(registry, AgentStateMachine(), sleep = { })
        val store = InMemoryMacroStore().apply { save(Macro("greet", listOf(MacroStep("recording", emptyMap())))) }
        val fakeScheduler = FakeScheduler()
        val scheduler = MacroScheduler(store, MacroExecutor(toolExecutor), fakeScheduler)

        scheduler.scheduleRecurring("greet", periodMs = 1000)
        fakeScheduler.scheduled.single().task.invoke()

        assertEquals(1, tool.invocations)
    }

    @Test
    fun `an edit to the macro between firings is picked up on the next firing`() {
        val tool = ScheduledRecordingTool()
        val registry = ToolRegistry().apply { register(tool) }
        val toolExecutor = ToolExecutor(registry, AgentStateMachine(), sleep = { })
        val store = InMemoryMacroStore().apply { save(Macro("greet", emptyList())) } // zero steps at first
        val fakeScheduler = FakeScheduler()
        val scheduler = MacroScheduler(store, MacroExecutor(toolExecutor), fakeScheduler)
        scheduler.scheduleRecurring("greet", periodMs = 1000)

        fakeScheduler.scheduled.single().task.invoke()
        assertEquals(0, tool.invocations)

        store.save(Macro("greet", listOf(MacroStep("recording", emptyMap()))))
        fakeScheduler.scheduled.single().task.invoke()
        assertEquals(1, tool.invocations)
    }

    @Test
    fun `logs macro_scheduled and macro_unscheduled`() {
        val logger = SchedulerRecordingLogger()
        val (scheduler, store, _) = newSetup(logger)
        store.save(Macro("greet", emptyList()))

        scheduler.scheduleRecurring("greet", periodMs = 1000)
        scheduler.cancel("greet")

        assertEquals(1, logger.events.count { it.message == "macro_scheduled" })
        assertEquals(1, logger.events.count { it.message == "macro_unscheduled" })
    }

    @Test
    fun `logs scheduled_macro_ran with the outcome on a successful firing`() {
        val tool = ScheduledRecordingTool()
        val registry = ToolRegistry().apply { register(tool) }
        val toolExecutor = ToolExecutor(registry, AgentStateMachine(), sleep = { })
        val store = InMemoryMacroStore().apply { save(Macro("greet", listOf(MacroStep("recording", emptyMap())))) }
        val fakeScheduler = FakeScheduler()
        val logger = SchedulerRecordingLogger()
        val scheduler = MacroScheduler(store, MacroExecutor(toolExecutor), fakeScheduler, logger)
        scheduler.scheduleRecurring("greet", periodMs = 1000)

        fakeScheduler.scheduled.single().task.invoke()

        val event = logger.events.single { it.message == "scheduled_macro_ran" }
        assertEquals("Completed", event.fields["outcome"])
    }

    @Test
    fun `logs scheduled_macro_missing when the macro was deleted before a firing`() {
        val tool = ScheduledRecordingTool()
        val registry = ToolRegistry().apply { register(tool) }
        val toolExecutor = ToolExecutor(registry, AgentStateMachine(), sleep = { })
        val store = InMemoryMacroStore().apply { save(Macro("greet", emptyList())) }
        val fakeScheduler = FakeScheduler()
        val logger = SchedulerRecordingLogger()
        val scheduler = MacroScheduler(store, MacroExecutor(toolExecutor), fakeScheduler, logger)
        scheduler.scheduleRecurring("greet", periodMs = 1000)
        store.delete("greet")

        fakeScheduler.scheduled.single().task.invoke()

        assertEquals(1, logger.events.count { it.message == "scheduled_macro_missing" })
        assertEquals(0, tool.invocations)
    }

    @Test
    fun `a firing that throws is caught and logged rather than propagating`() {
        val throwingStore = object : MacroStore {
            override fun save(macro: Macro) = throw UnsupportedOperationException()
            override fun load(name: String): Macro = throw RuntimeException("boom")
            override fun list(): List<String> = emptyList()
            override fun delete(name: String) = false
        }
        val registry = ToolRegistry()
        val toolExecutor = ToolExecutor(registry, AgentStateMachine(), sleep = { })
        val fakeScheduler = FakeScheduler()
        val logger = SchedulerRecordingLogger()
        val scheduler = MacroScheduler(throwingStore, MacroExecutor(toolExecutor), fakeScheduler, logger)
        scheduler.scheduleRecurring("greet", periodMs = 1000)

        fakeScheduler.scheduled.single().task.invoke() // must not throw

        assertEquals(1, logger.events.count { it.message == "scheduled_macro_load_failed" })
    }
}
