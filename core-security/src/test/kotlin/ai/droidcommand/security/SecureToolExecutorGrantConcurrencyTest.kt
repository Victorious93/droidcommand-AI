package ai.droidcommand.security

import ai.droidcommand.agent.AgentStateMachine
import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolExecutor
import ai.droidcommand.agent.ToolRegistry
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolSpec
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class BlockingGrantedTool : Tool {
    override val spec = ToolSpec(name = "granted", description = "d", grantCapability = "cap")
    val invocations = AtomicInteger()
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)

    override fun execute(input: Map<String, String>): ToolResult {
        invocations.incrementAndGet()
        entered.countDown()
        release.await(5, TimeUnit.SECONDS)
        return ToolResult.Success("ran")
    }
}

class SecureToolExecutorGrantConcurrencyTest {
    @Test
    fun `a single-use grant cannot be spent twice by two concurrent calls`() {
        val tool = BlockingGrantedTool()
        val registry = ToolRegistry().apply { register(tool) }
        val stateMachine = AgentStateMachine()
        val grants = InMemoryGrantStore().apply { issue(Grant(id = "g1", capability = "cap", singleUse = true)) }
        val secure = SecureToolExecutor(
            registry,
            ToolExecutor(registry, stateMachine, sleep = { }),
            stateMachine,
            SecurityPolicyEnforcer(SecurityPolicy()),
            ApprovalPrompt { true },
            grantStore = grants,
        )

        val results = java.util.Collections.synchronizedList(mutableListOf<ToolResult>())
        val first = Thread { results += secure.run("granted", emptyMap(), grantId = "g1") }.apply { start() }
        assertTrue(tool.entered.await(5, TimeUnit.SECONDS))
        val second = Thread { results += secure.run("granted", emptyMap(), grantId = "g1") }.apply { start() }
        Thread.sleep(150) // let the second call reach (and, if unguarded, pass) the grant check
        tool.release.countDown()
        first.join(5_000)
        second.join(5_000)

        assertEquals(1, tool.invocations.get(), "the tool must run exactly once for one single-use grant")
        assertEquals(1, results.count { it is ToolResult.Success })
        assertEquals(1, results.count { it is ToolResult.Failure })
    }
}
