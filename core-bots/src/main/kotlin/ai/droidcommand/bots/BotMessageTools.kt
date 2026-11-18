package ai.droidcommand.bots

import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolSpec

/** Tool names, so the Bot maker and [ScopedToolRunner] allow-lists refer to one spelling. */
object BotToolNames {
    const val MESSAGE = "bot_message"
    const val PEERS = "bot_peers"
    const val SANDBOX_CREATE = "sandbox_create"
    const val SANDBOX_RUN = "sandbox_run"
    const val SANDBOX_DELETE = "sandbox_delete"
}

/**
 * Lets the bot that is currently running send one message to a peer the owner allowed it to talk to, and read the
 * reply. Built per run from that run's [BotConversation], so the sender is always the bot actually running and
 * a bot cannot pose as another. A caller must also put `bot_message` on the bot's allow-list (the app does this only
 * for a bot whose owner ticked at least one peer for it).
 *
 * A target that is not one of the sender's allowed, enabled peers gets the same plain answer whether or not such a
 * bot exists, so a bot cannot probe which other bots the owner has made. The attempt is still audited by the bus.
 */
class BotMessageTool(private val conversation: BotConversation) : Tool {
    override val spec = ToolSpec(
        name = BotToolNames.MESSAGE,
        description = "Sends one message to another helper bot you are allowed to talk to and returns its reply. " +
            "Inputs: 'to' (the bot's name from bot_peers) and 'message'.",
        securityLevel = SecurityLevel.NORMAL,
        requiresConfirmation = false,
    )

    override fun execute(input: Map<String, String>): ToolResult {
        val to = input["to"]?.trim().orEmpty()
        val message = input["message"].orEmpty()
        if (to.isEmpty()) return ToolResult.Failure("Missing required input 'to'")
        if (message.isBlank()) return ToolResult.Failure("Missing required input 'message'")
        val target = conversation.peers().filter { it.id == to || it.name.equals(to, ignoreCase = true) }
        if (target.size != 1) {
            // Still goes through the bus so the attempt is audited; the answer never says why.
            conversation.send(conversation.speaker, to, message)
            return ToolResult.Failure(
                if (target.isEmpty()) "No bot you are allowed to message matches '$to'. Use bot_peers to see who you can message." else "More than one allowed bot matches '$to'.",
            )
        }
        return when (val outcome = conversation.send(conversation.speaker, target.single().id, message)) {
            is BotMessageOutcome.Delivered -> ToolResult.Success(outcome.reply)
            is BotMessageOutcome.Denied -> ToolResult.Failure("Message not sent: ${outcome.reason}")
        }
    }
}

/** Lists the bots the running bot may message. Names only; nothing about other bots' instructions or tools. */
class BotPeersTool(private val conversation: BotConversation) : Tool {
    override val spec = ToolSpec(
        name = BotToolNames.PEERS,
        description = "Lists the other helper bots you are allowed to message with bot_message.",
        securityLevel = SecurityLevel.NORMAL,
        requiresConfirmation = false,
    )

    override fun execute(input: Map<String, String>): ToolResult {
        val peers = conversation.peers()
        return ToolResult.Success(if (peers.isEmpty()) "You are not allowed to message any other bot." else peers.joinToString("\n") { "- ${it.name}" })
    }
}
