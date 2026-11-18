package ai.droidcommand.bots

import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolSpec
import ai.droidcommand.security.AuditEvent
import ai.droidcommand.security.AuditEventType
import ai.droidcommand.security.AuditLog

/**
 * Wraps [BotMessageSendTool] so every send, delivered or refused, lands in the Activity log under the sending bot's id.
 * The text of the message is never logged, only the recipient id and its length; a refusal logs the bus's reason.
 *
 * The entry is written after the bus has decided, so a full audit log does not stop an (inert, mailbox-only) message,
 * unlike the sandbox tools, which are audited first and refuse to run unrecorded.
 */
class AuditedBotMessageSendTool(
    private val inner: BotMessageSendTool,
    private val senderId: String,
    private val auditLog: AuditLog,
) : Tool {
    override val spec: ToolSpec get() = inner.spec

    override fun execute(input: Map<String, String>): ToolResult {
        val result = inner.execute(input)
        val to = input["to"].orEmpty().trim().take(80)
        val event = when (result) {
            is ToolResult.Success -> AuditEvent(AuditEventType.BOT_MESSAGE_SENT, senderId, "to '$to' (${input["text"].orEmpty().trim().length} chars)")
            is ToolResult.Failure -> AuditEvent(AuditEventType.BOT_MESSAGE_DENIED, senderId, "to '$to': ${result.reason.take(200)}")
            else -> return result
        }
        auditLog.record(event)
        return result
    }
}
