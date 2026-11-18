package ai.droidcommand.bots

import ai.droidcommand.agent.AgentMode
import ai.droidcommand.agent.Initiator
import ai.droidcommand.agent.RetryPolicy
import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolRunner
import ai.droidcommand.agent.ToolSpec

/**
 * The tools bound to one running bot (its own `bot_message`, `sandbox_run`, …). They cannot live in the app's shared
 * registry because each is tied to one bot and one conversation, so a bot gets its own small set and its own
 * approval-gated runner for it; every other tool name goes to the app's normal runner unchanged.
 */
class BotToolKit(val tools: List<Tool>) {
    val specs: List<ToolSpec> = tools.map { it.spec }
    val names: Set<String> = specs.map { it.name }.toSet()

    init {
        require(names.size == tools.size) { "duplicate tool name in a bot's tool kit" }
    }
}

/** Sends the names in [ownNames] to [own] and every other name to [fallback]. Adds no permission of its own. */
class RoutingToolRunner(
    private val ownNames: Set<String>,
    private val own: ToolRunner,
    private val fallback: ToolRunner,
) : ToolRunner {
    override fun run(
        toolName: String,
        input: Map<String, String>,
        retryPolicy: RetryPolicy,
        isCancelled: () -> Boolean,
        mode: AgentMode?,
        initiator: Initiator?,
        grantId: String?,
    ): ToolResult =
        (if (toolName in ownNames) own else fallback).run(toolName, input, retryPolicy, isCancelled, mode, initiator, grantId)
}
