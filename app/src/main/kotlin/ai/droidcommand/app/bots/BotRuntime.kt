package ai.droidcommand.app.bots

import ai.droidcommand.agent.AgentStateMachine
import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolExecutor
import ai.droidcommand.agent.ToolRegistry
import ai.droidcommand.agent.ToolRunner
import ai.droidcommand.bots.AuditedBotMessageSendTool
import ai.droidcommand.bots.BotMessageBus
import ai.droidcommand.bots.BotMessageReadTool
import ai.droidcommand.bots.BotMessageSendTool
import ai.droidcommand.bots.BotPeerPolicy
import ai.droidcommand.bots.BotToolKit
import ai.droidcommand.bots.ProotSandboxExecutor
import ai.droidcommand.bots.RoutingToolRunner
import ai.droidcommand.bots.SandboxCreateTool
import ai.droidcommand.bots.SandboxDeleteTool
import ai.droidcommand.bots.SandboxRunTool
import ai.droidcommand.config.AssistantCapability
import ai.droidcommand.config.AssistantCapabilitySettingsStore
import ai.droidcommand.security.ApprovalPrompt
import ai.droidcommand.security.JsonFileAuditLog
import ai.droidcommand.security.SecureToolExecutor
import ai.droidcommand.security.SecurityPolicy
import ai.droidcommand.security.SecurityPolicyEnforcer
import ai.droidcommand.termux.TermuxExecutor
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** A bot another bot may message: its [name] for reading, its [id] for the `to` field of `bot_message_send`. */
data class BotPeerRef(val id: String, val name: String)

/**
 * The two things a helper bot can be granted beyond its ticked tools, both set per bot in the Bot maker and both
 * off until the owner turns them on:
 *
 *  - **Talking to other bots** (`bot_message_send`, `bot_message_read`): only to bots in that bot's own "Talks to"
 *    list. The permission is the directed, default-deny [BotPeerPolicy], rebuilt from the saved bots on every send
 *    so an edit or a delete takes effect at once. Messages go to the recipient's in-memory mailbox
 *    ([BotMessageBus]): nothing runs the recipient. It reads them the next time the owner runs it, and they are lost
 *    when the app restarts. Every send, delivered or refused, is written to the Activity log.
 *  - **Its own Linux sandbox** (`sandbox_create`, `sandbox_run`, `sandbox_delete`): a proot-distro container in
 *    Termux/VictorSuite for that bot alone. Needs the Linux environment switch under Advanced as well as the bot's
 *    own switch, checked again on every call.
 *
 * Every tool here goes through its own [SecureToolExecutor] with the app's normal security policy, approval dialog
 * and audit log, so the sandbox tools ask the owner each time exactly like other terminal tools.
 */
@Singleton
class BotRuntime @Inject constructor(
    @ApplicationContext private val context: Context,
    private val capabilities: AssistantCapabilitySettingsStore,
    private val stateMachine: AgentStateMachine,
    private val policy: SecurityPolicy,
    private val approval: ApprovalPrompt,
    private val auditLog: JsonFileAuditLog,
    private val termux: TermuxExecutor,
) {
    private val prefs get() = context.getSharedPreferences(BOT_PREFS, Context.MODE_PRIVATE)

    /** Saved bots, read fresh each time so a bot edited in the Bot maker is seen at once. */
    fun savedBots(): List<HelperBot> = HelperBots.decode(prefs.getString(BOTS_KEY, null))

    fun linuxEnvironmentOn(): Boolean = capabilities.load().isEnabled(AssistantCapability.LINUX_ENVIRONMENT)

    /** Built from the saved bots each time; links to a bot that no longer exists, or to itself, grant nothing. */
    private fun peerPolicy(): BotPeerPolicy {
        val bots = savedBots()
        val ids = bots.map { it.id }.toSet()
        return bots.fold(BotPeerPolicy.NONE) { acc, bot ->
            bot.talksTo.filter { it != bot.id && it in ids }.fold(acc) { p, to -> p.allow(bot.id, to) }
        }
    }

    private val bus = BotMessageBus(policy = ::peerPolicy)

    private fun nameOf(id: String): String? = savedBots().firstOrNull { it.id == id }?.name

    /** The bots [bot] may message right now, by name and id, sorted by name. */
    fun peersOf(bot: HelperBot): List<BotPeerRef> =
        peerPolicy().targetsOf(bot.id).mapNotNull { id -> nameOf(id)?.let { BotPeerRef(id, it) } }.sortedBy { it.name.lowercase() }

    /** Messages waiting in [bot]'s mailbox (for the system prompt; reading them is the bot's own `bot_message_read`). */
    fun unreadFor(bot: HelperBot): Int = bus.pendingCount(bot.id)

    /** Drops a deleted bot's mailbox and send history. */
    fun forget(botId: String) = bus.forget(botId)

    /**
     * The extra tools [bot] has been granted, or null when it has none. Re-reads the saved bot (and the Linux switch)
     * on every sandbox call, so turning either off in the Bot maker or Advanced takes effect even mid-run.
     */
    fun kitFor(bot: HelperBot): BotToolKit? {
        val tools = mutableListOf<Tool>()
        if (bot.talksTo.isNotEmpty()) {
            tools += AuditedBotMessageSendTool(BotMessageSendTool(bot.id, bus, ::nameOf), bot.id, auditLog)
            tools += BotMessageReadTool(bot.id, bus, ::nameOf)
        }
        if (bot.sandbox && linuxEnvironmentOn()) {
            val on = { linuxEnvironmentOn() && savedBots().firstOrNull { it.id == bot.id }?.sandbox == true }
            val executor = ProotSandboxExecutor(termux)
            tools += SandboxCreateTool(bot.id, executor, on, auditLog)
            tools += SandboxRunTool(bot.id, executor, on, auditLog)
            tools += SandboxDeleteTool(bot.id, executor, on, auditLog)
        }
        return if (tools.isEmpty()) null else BotToolKit(tools)
    }

    /** [appRunner] for everything but the kit's own tools, which run through the same policy, approval and audit. */
    fun runnerFor(kit: BotToolKit, appRunner: ToolRunner): ToolRunner {
        val registry = ToolRegistry().apply { kit.tools.forEach { register(it) } }
        val own = SecureToolExecutor(
            registry,
            ToolExecutor(registry, stateMachine),
            stateMachine,
            SecurityPolicyEnforcer(policy),
            approval,
            auditLog = auditLog,
        )
        return RoutingToolRunner(kit.names, own, appRunner)
    }

    companion object {
        const val BOT_PREFS = "helper_bots"
        const val BOTS_KEY = "bots.v1"
    }
}
