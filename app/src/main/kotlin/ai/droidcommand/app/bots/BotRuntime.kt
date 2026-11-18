package ai.droidcommand.app.bots

import ai.droidcommand.agent.AgentStateMachine
import ai.droidcommand.agent.InMemoryConversationStore
import ai.droidcommand.agent.ToolExecutor
import ai.droidcommand.agent.ToolRegistry
import ai.droidcommand.agent.ToolRunner
import ai.droidcommand.app.localmodels.LocalModelRuntime
import ai.droidcommand.bots.BotBus
import ai.droidcommand.bots.BotConversation
import ai.droidcommand.bots.BotDirectory
import ai.droidcommand.bots.BotMessageTool
import ai.droidcommand.bots.BotPeer
import ai.droidcommand.bots.BotPeersTool
import ai.droidcommand.bots.BotResponder
import ai.droidcommand.bots.BotToolKit
import ai.droidcommand.bots.ProotSandboxExecutor
import ai.droidcommand.bots.RoutingToolRunner
import ai.droidcommand.bots.SandboxCreateTool
import ai.droidcommand.bots.SandboxDeleteTool
import ai.droidcommand.bots.SandboxRunTool
import ai.droidcommand.config.AssistantCapability
import ai.droidcommand.config.AssistantCapabilitySettingsStore
import ai.droidcommand.config.EncryptedSecretsVault
import ai.droidcommand.llm.factory.ChatResult
import ai.droidcommand.llm.factory.ChatSession
import ai.droidcommand.llm.factory.CloudPromptClient
import ai.droidcommand.llm.factory.CloudProviderCatalog
import ai.droidcommand.llm.routing.PolicyInference
import ai.droidcommand.remote.HttpTransport
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

/**
 * The two things a helper bot can be granted beyond its ticked tools, both set per bot in the Bot maker and both
 * off until the owner turns them on:
 *
 *  - **Talking to other bots** (`bot_message`, `bot_peers`): only to bots in that bot's own "Talks to" list. A peer
 *    answers in text only; it does not run tools while answering. See [ai.droidcommand.bots.BotBus] for the loop,
 *    depth and message limits, and the audit trail.
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
    private val vault: EncryptedSecretsVault,
    private val transport: HttpTransport,
    private val localModels: LocalModelRuntime,
    private val inference: PolicyInference,
) {
    private val prefs get() = context.getSharedPreferences(BOT_PREFS, Context.MODE_PRIVATE)

    /** Saved bots, read fresh each time so a bot edited in the Bot maker is seen at once. */
    fun savedBots(): List<HelperBot> = HelperBots.decode(prefs.getString(BOTS_KEY, null))

    fun linuxEnvironmentOn(): Boolean = capabilities.load().isEnabled(AssistantCapability.LINUX_ENVIRONMENT)

    private val directory = object : BotDirectory {
        override fun find(id: String): BotPeer? = savedBots().firstOrNull { it.id == id }?.toPeer()

        override fun all(): List<BotPeer> = savedBots().map { it.toPeer() }
    }

    private fun HelperBot.toPeer() = BotPeer(id = id, name = name, enabled = true, mayMessage = talksTo)

    private val bus = BotBus(directory, BotResponder { recipient, _, framed, _ -> answerAsPeer(recipient.id, framed) }, auditLog)

    /** Names of the bots [bot] may message right now (for the system prompt). */
    fun peerNames(bot: HelperBot): List<String> = bus.peersOf(bot.id).map { it.name }

    /**
     * The extra tools [bot] has been granted, or null when it has none. Re-read the saved bot (and the Linux switch)
     * on every sandbox call, so turning either off in the Bot maker or Advanced takes effect even mid-run.
     */
    fun kitFor(bot: HelperBot): BotToolKit? {
        val tools = mutableListOf<ai.droidcommand.agent.Tool>()
        if (bot.talksTo.isNotEmpty()) {
            val conversation: BotConversation = bus.open(bot.id)
            tools += BotMessageTool(conversation)
            tools += BotPeersTool(conversation)
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

    /**
     * One reply from [recipientId] to a message another bot sent it. Text only, nothing stored, and it goes where the
     * owner's inference policy says (the same choice as the companion-assist pop-ups): the recipient's own
     * instructions, then the framed message. Throws on failure so the bus reports it as a refused message.
     */
    private fun answerAsPeer(recipientId: String, framedMessage: String): String {
        val recipient = savedBots().firstOrNull { it.id == recipientId } ?: error("that bot no longer exists")
        val prompt = HelperBots.systemPrompt(recipient, emptySet()) + "\n\n" + framedMessage
        val chat = context.getSharedPreferences(CHAT_PREFS, Context.MODE_PRIVATE)
        val localId = chat.getString(KEY_LOCAL_MODEL, null)?.takeIf { it.isNotBlank() }
        val result = when {
            inference.policy().routed -> inference.ask("Bot to bot message", prompt)
            localId != null -> {
                localModels.list()
                val session = ChatSession(vault, InMemoryConversationStore(), transport = transport, local = localModels.resources)
                try {
                    session.sendLocal(localId, prompt)
                } finally {
                    session.close()
                }
            }
            else -> {
                val spec = CloudProviderCatalog.byId(chat.getString(KEY_PROVIDER, null).orEmpty()) ?: CloudProviderCatalog.all.first()
                val model = recipient.model.ifBlank { chat.getString("model.${spec.id}", null) ?: spec.defaultModel }
                CloudPromptClient(vault, transport).ask(spec, model, prompt)
            }
        }
        return when (result) {
            is ChatResult.Reply -> result.text
            is ChatResult.Failure -> error(result.message)
        }
    }

    companion object {
        const val BOT_PREFS = "helper_bots"
        const val BOTS_KEY = "bots.v1"
        private const val CHAT_PREFS = "chat_prefs"
        private const val KEY_PROVIDER = "provider"
        private const val KEY_LOCAL_MODEL = "local_model_id"
    }
}
