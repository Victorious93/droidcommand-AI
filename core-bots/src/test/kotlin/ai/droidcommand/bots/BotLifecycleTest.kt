package ai.droidcommand.bots

import ai.droidcommand.agent.AgentMode
import ai.droidcommand.agent.Formality
import ai.droidcommand.agent.HumorProfile
import ai.droidcommand.agent.InMemoryPersonaStore
import ai.droidcommand.agent.Initiator
import ai.droidcommand.agent.Persona
import ai.droidcommand.agent.PersonaCategory
import ai.droidcommand.agent.PersonaStore
import ai.droidcommand.agent.RetryPolicy
import ai.droidcommand.agent.StructureProfile
import ai.droidcommand.agent.StyleProfile
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolRunner
import ai.droidcommand.agent.Verbosity
import ai.droidcommand.agent.VocabProfile
import ai.droidcommand.security.AuditEventType
import ai.droidcommand.security.InMemoryAuditLog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun testPersona(id: String, contribution: String) = Persona(
    id = id,
    name = "Test Persona",
    category = PersonaCategory.CUSTOM,
    sourceConversations = emptyList(),
    styleCharacteristics = StyleProfile(
        tone = "concise",
        vocabulary = VocabProfile("simple"),
        sentenceStructure = StructureProfile("short", "minimal"),
        formality = Formality.NEUTRAL,
        verbosity = Verbosity.CONCISE,
        humor = HumorProfile(false),
        responseStructure = "plain",
    ),
    contextContribution = contribution,
    version = "1",
    enabled = true,
)

private fun testBot(id: String = "b1", enabled: Boolean = true) = BotRecipe(
    id = id,
    name = "Test Bot",
    personaId = "p1",
    providerType = "anthropic",
    memoryFolder = "Bots/$id",
    enabled = enabled,
)

/** Wraps a [PersonaStore] to record whether [load] was ever called, so a test can prove it was never reached. */
private class RecordingPersonaStore(private val delegate: PersonaStore) : PersonaStore by delegate {
    var loaded = false

    override fun load(id: String): Persona? {
        loaded = true
        return delegate.load(id)
    }
}

private class TouchFlagToolRunner : ToolRunner {
    var touched = false

    override fun run(
        toolName: String,
        input: Map<String, String>,
        retryPolicy: RetryPolicy,
        isCancelled: () -> Boolean,
        mode: AgentMode?,
        initiator: Initiator?,
        grantId: String?,
    ): ToolResult {
        touched = true
        return ToolResult.Success(toolName)
    }
}

class BotLifecycleTest {
    @Test
    fun `pause disables the recipe, persists it, and records one BOT_PAUSED event`() {
        val store = InMemoryBotStore()
        val auditLog = InMemoryAuditLog()
        val recipe = testBot()
        store.save(recipe)

        val paused = BotLifecycle.pause(recipe, store, auditLog)

        assertFalse(paused.enabled)
        assertFalse(store.load("b1")!!.enabled)
        val events = auditLog.all()
        assertEquals(1, events.size)
        assertEquals(AuditEventType.BOT_PAUSED, events.single().type)
        assertEquals("b1", events.single().subject)
    }

    @Test
    fun `resume enables the recipe, persists it, and records one BOT_RESUMED event`() {
        val store = InMemoryBotStore()
        val auditLog = InMemoryAuditLog()
        val recipe = testBot(enabled = false)
        store.save(recipe)

        val resumed = BotLifecycle.resume(recipe, store, auditLog)

        assertTrue(resumed.enabled)
        assertTrue(store.load("b1")!!.enabled)
        val events = auditLog.all()
        assertEquals(1, events.size)
        assertEquals(AuditEventType.BOT_RESUMED, events.single().type)
    }

    @Test
    fun `pausing an already-paused bot is a no-op in effect but still re-saves and re-logs`() {
        val store = InMemoryBotStore()
        val auditLog = InMemoryAuditLog()
        val recipe = testBot(enabled = false)
        store.save(recipe)

        val paused = BotLifecycle.pause(recipe, store, auditLog)

        assertFalse(paused.enabled)
        assertEquals(1, auditLog.all().size)
    }

    @Test
    fun `resuming an already-enabled bot is a no-op in effect but still re-saves and re-logs`() {
        val store = InMemoryBotStore()
        val auditLog = InMemoryAuditLog()
        val recipe = testBot(enabled = true)
        store.save(recipe)

        val resumed = BotLifecycle.resume(recipe, store, auditLog)

        assertTrue(resumed.enabled)
        assertEquals(1, auditLog.all().size)
    }

    @Test
    fun `startRun on a paused bot throws without touching the persona store, the delegate, or the audit log`() {
        val personas = RecordingPersonaStore(InMemoryPersonaStore().apply { save(testPersona("p1", "Write concisely.")) })
        val delegate = TouchFlagToolRunner()
        val auditLog = InMemoryAuditLog()
        val recipe = testBot(enabled = false)

        assertFailsWith<BotPaused> { BotLifecycle.startRun(recipe, personas, delegate, auditLog) }

        assertFalse(personas.loaded)
        assertFalse(delegate.touched)
        assertTrue(auditLog.all().isEmpty())
    }

    @Test
    fun `startRun on an enabled bot records one BOT_RUN_STARTED event and returns the composed run`() {
        val personas = InMemoryPersonaStore().apply { save(testPersona("p1", "Write concisely.")) }
        val delegate = TouchFlagToolRunner()
        val auditLog = InMemoryAuditLog()
        val recipe = testBot(enabled = true).copy(allowedTools = setOf("allowed_tool"))

        val run = BotLifecycle.startRun(recipe, personas, delegate, auditLog)

        assertEquals("Write concisely.", run.systemPrompt)
        run.executor.run("allowed_tool", emptyMap())
        assertTrue(delegate.touched)
        val events = auditLog.all()
        assertEquals(1, events.size)
        assertEquals(AuditEventType.BOT_RUN_STARTED, events.single().type)
        assertEquals("b1", events.single().subject)
    }
}
