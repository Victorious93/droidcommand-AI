package ai.droidcommand.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContextManagerTest {
    private val task = Task("t1", "Do the thing", verificationCriteria = listOf("it got done"))

    @Test
    fun `buildContext with no registered providers still includes the mandatory task contribution`() {
        val manager = DefaultContextManager()
        val snapshot = manager.buildContext(task, tokenBudget = 1000)

        assertEquals(1, snapshot.included.size)
        assertEquals(ContextKind.TASK, snapshot.included.single().kind)
        assertTrue(snapshot.included.single().content.contains("Do the thing"))
        assertTrue(snapshot.included.single().content.contains("it got done"))
        assertTrue(snapshot.omitted.isEmpty())
    }

    @Test
    fun `buildContext with a null task and no providers produces an empty snapshot`() {
        val manager = DefaultContextManager()
        val snapshot = manager.buildContext(null, tokenBudget = 1000)

        assertTrue(snapshot.included.isEmpty())
        assertTrue(snapshot.omitted.isEmpty())
        assertEquals(0, snapshot.tokenBudgetUsed)
        assertEquals(1000, snapshot.tokenBudgetRemaining)
    }

    @Test
    fun `contributions are ordered by ContextKind declaration order`() {
        val manager = DefaultContextManager()
        manager.registerProvider(ContextKind.CONVERSATION) { ContextContribution(ContextKind.CONVERSATION, "conv", "history") }
        manager.registerProvider(ContextKind.KNOWLEDGE) { ContextContribution(ContextKind.KNOWLEDGE, "kg", "fact") }
        manager.registerProvider(ContextKind.SYSTEM_INSTRUCTIONS) { ContextContribution(ContextKind.SYSTEM_INSTRUCTIONS, "sys", "be helpful") }

        val snapshot = manager.buildContext(task, tokenBudget = 1000)
        val kindsInOrder = snapshot.included.map { it.kind }

        assertEquals(listOf(ContextKind.TASK, ContextKind.SYSTEM_INSTRUCTIONS, ContextKind.KNOWLEDGE, ContextKind.CONVERSATION), kindsInOrder)
    }

    @Test
    fun `FILES sits between SUMMARY and CONVERSATION, matching CAP-003's P0-3 chain position`() {
        val manager = DefaultContextManager()
        manager.registerProvider(ContextKind.CONVERSATION) { ContextContribution(ContextKind.CONVERSATION, "conv", "history") }
        manager.registerProvider(ContextKind.FILES) { ContextContribution(ContextKind.FILES, "files", "listing") }
        manager.registerProvider(ContextKind.SUMMARY) { ContextContribution(ContextKind.SUMMARY, "summary", "tl;dr") }

        val snapshot = manager.buildContext(task, tokenBudget = 1000)
        val kindsInOrder = snapshot.included.map { it.kind }

        assertEquals(listOf(ContextKind.TASK, ContextKind.SUMMARY, ContextKind.FILES, ContextKind.CONVERSATION), kindsInOrder)
    }

    @Test
    fun `low-priority contributions are omitted once the budget is exhausted`() {
        val manager = DefaultContextManager()
        val bigConversation = "x".repeat(4000) // ~1000 estimated tokens
        manager.registerProvider(ContextKind.CONVERSATION) { ContextContribution(ContextKind.CONVERSATION, "conv", bigConversation) }
        manager.registerProvider(ContextKind.KNOWLEDGE) { ContextContribution(ContextKind.KNOWLEDGE, "kg", "short fact") }

        val snapshot = manager.buildContext(task, tokenBudget = 20)

        assertTrue(snapshot.included.any { it.kind == ContextKind.TASK })
        assertTrue(snapshot.included.any { it.kind == ContextKind.KNOWLEDGE })
        assertTrue(snapshot.omitted.any { it.kind == ContextKind.CONVERSATION })
        assertTrue(snapshot.tokenBudgetUsed <= snapshot.tokenBudget)
    }

    @Test
    fun `mandatory contributions are included even when they alone exceed the budget`() {
        val manager = DefaultContextManager()
        val hugeTask = Task("huge", "y".repeat(4000))

        val snapshot = manager.buildContext(hugeTask, tokenBudget = 10)

        assertEquals(1, snapshot.included.size)
        assertEquals(ContextKind.TASK, snapshot.included.single().kind)
        assertTrue(snapshot.tokenBudgetUsed > snapshot.tokenBudget)
        assertEquals(0, snapshot.tokenBudgetRemaining)
    }

    @Test
    fun `a provider returning null contributes nothing`() {
        val manager = DefaultContextManager()
        manager.registerProvider(ContextKind.KNOWLEDGE) { null }

        val snapshot = manager.buildContext(task, tokenBudget = 1000)

        assertTrue(snapshot.included.none { it.kind == ContextKind.KNOWLEDGE })
    }

    @Test
    fun `multiple providers under the same kind all contribute, in registration order`() {
        val manager = DefaultContextManager()
        manager.registerProvider(ContextKind.KNOWLEDGE) { ContextContribution(ContextKind.KNOWLEDGE, "first", "a") }
        manager.registerProvider(ContextKind.KNOWLEDGE) { ContextContribution(ContextKind.KNOWLEDGE, "second", "b") }

        val snapshot = manager.buildContext(task, tokenBudget = 1000)
        val knowledgeSources = snapshot.included.filter { it.kind == ContextKind.KNOWLEDGE }.map { it.sourceId }

        assertEquals(listOf("first", "second"), knowledgeSources)
    }

    @Test
    fun `inspectContext reports registered provider counts and the last snapshot`() {
        val manager = DefaultContextManager()
        manager.registerProvider(ContextKind.KNOWLEDGE) { ContextContribution(ContextKind.KNOWLEDGE, "kg", "fact") }
        manager.registerProvider(ContextKind.KNOWLEDGE) { ContextContribution(ContextKind.KNOWLEDGE, "kg2", "fact2") }

        val before = manager.inspectContext()
        assertEquals(null, before.lastSnapshot)
        assertEquals(2, before.registeredProviders[ContextKind.KNOWLEDGE])

        val snapshot = manager.buildContext(task, tokenBudget = 1000)
        val after = manager.inspectContext()

        assertEquals(snapshot, after.lastSnapshot)
    }

    @Test
    fun `buildContext is deterministic across repeated calls with identical input`() {
        val manager = DefaultContextManager()
        manager.registerProvider(ContextKind.KNOWLEDGE) { ContextContribution(ContextKind.KNOWLEDGE, "kg", "fact") }
        manager.registerProvider(ContextKind.CONVERSATION) { ContextContribution(ContextKind.CONVERSATION, "conv", "history") }

        val first = manager.buildContext(task, tokenBudget = 1000)
        val second = manager.buildContext(task, tokenBudget = 1000)

        assertEquals(first, second)
    }

    @Test
    fun `buildContext rejects a non-positive token budget`() {
        val manager = DefaultContextManager()
        try {
            manager.buildContext(task, tokenBudget = 0)
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("tokenBudget"))
        }
    }
}

class ConversationContextProviderTest {
    @Test
    fun `formats every message with its role`() {
        val context = ConversationContext().append(Role.USER, "hello").append(Role.ASSISTANT, "hi there")
        val provider = ConversationContextProvider(context)

        val contribution = provider.provide(null)!!

        assertEquals(ContextKind.CONVERSATION, contribution.kind)
        assertTrue(contribution.content.contains("USER: hello"))
        assertTrue(contribution.content.contains("ASSISTANT: hi there"))
    }

    @Test
    fun `an empty conversation contributes nothing`() {
        val provider = ConversationContextProvider(ConversationContext())
        assertEquals(null, provider.provide(null))
    }
}

class KnowledgeContextProviderTest {
    @Test
    fun `formats entries returned by the query function`() {
        val store = InMemoryKnowledgeStore()
        store.save(KnowledgeEntry(id = "k1", content = "the sky is blue", source = "manual", tags = setOf("facts")))
        val provider = KnowledgeContextProvider { store.findByTag("facts") }

        val contribution = provider.provide(null)!!

        assertEquals(ContextKind.KNOWLEDGE, contribution.kind)
        assertTrue(contribution.content.contains("the sky is blue"))
    }

    @Test
    fun `an empty query result contributes nothing`() {
        val provider = KnowledgeContextProvider { emptyList() }
        assertEquals(null, provider.provide(null))
    }
}
