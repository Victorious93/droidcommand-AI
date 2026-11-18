package ai.droidcommand.bots

/** Names of the tools a running bot can be given beyond its ticked ones, so allow-lists and prompts share one spelling. */
object BotToolNames {
    const val MESSAGE_SEND = BotMessageSendTool.NAME
    const val MESSAGE_READ = BotMessageReadTool.NAME
    const val SANDBOX_CREATE = "sandbox_create"
    const val SANDBOX_RUN = "sandbox_run"
    const val SANDBOX_DELETE = "sandbox_delete"
}
