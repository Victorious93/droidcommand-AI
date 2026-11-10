package ai.droidcommand.app.bots

import ai.droidcommand.agent.ToolRegistry
import ai.droidcommand.agent.ToolRunner
import ai.droidcommand.agent.ToolSpec
import ai.droidcommand.bots.ScopedToolRunner

/**
 * Wires a helper bot's tool allow-list to the phone Chat path.
 * Tools not on the list are refused by [ScopedToolRunner] before the
 * delegate (the app's approval-gated [ToolRunner]) is called.
 */
object HelperBotTools {
    fun scopedRunner(delegate: ToolRunner, allowedTools: Set<String>): ToolRunner =
        ScopedToolRunner(delegate, allowedTools)

    fun offeredTools(registry: ToolRegistry, allowedTools: Set<String>): List<ToolSpec> =
        registry.list().filter { it.name in allowedTools }
}
