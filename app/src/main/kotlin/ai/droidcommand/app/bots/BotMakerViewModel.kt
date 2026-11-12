package ai.droidcommand.app.bots

import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.ToolRegistry
import ai.droidcommand.app.mcp.McpToolsRuntime
import ai.droidcommand.config.AssistantCapability
import ai.droidcommand.config.AssistantCapabilitySettingsStore
import ai.droidcommand.skills.mcp.McpBridgedTool
import ai.droidcommand.skills.mcp.McpResolution
import ai.droidcommand.skills.mcp.McpSelections
import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

data class BotMakerUi(
    val bots: List<HelperBot> = emptyList(),
    val tools: List<BotToolOption> = emptyList(),
    val enabled: Boolean = false,
    val error: String? = null,
    val status: String? = null,
    /** Bot waiting for the user to confirm it may use the listed risky tools. */
    val pendingSave: HelperBot? = null,
    val pendingRiskyTools: List<String> = emptyList(),
    val pendingDelete: HelperBot? = null,
    /** Per bot id: what its saved tools mean right now (which MCP tools work, which must be picked again). */
    val toolState: Map<String, McpResolution> = emptyMap(),
)

/** Helper bots are kept in this app's private storage on the phone; nothing is uploaded. */
@HiltViewModel
class BotMakerViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val registry: ToolRegistry,
    private val capabilities: AssistantCapabilitySettingsStore,
    private val mcpTools: McpToolsRuntime,
) : ViewModel() {
    private val prefs = context.getSharedPreferences("helper_bots", Context.MODE_PRIVATE)

    // MCP tools appear here only after they are loaded from the owner's MCP servers (see init).
    private var tools = toolOptions()

    private fun toolOptions(): List<BotToolOption> = registry.list().map {
        BotToolOption(
            name = it.name,
            description = it.description,
            risky = it.requiresConfirmation || it.requiresRoot || it.securityLevel != SecurityLevel.NORMAL,
        )
    }.sortedWith(compareBy({ it.risky }, { it.name }))

    private val savedBots = HelperBots.decode(prefs.getString(KEY, null))

    private val mutable = MutableStateFlow(
        BotMakerUi(
            bots = savedBots,
            toolState = toolStates(savedBots),
            tools = tools,
            enabled = capabilities.load().isEnabled(AssistantCapability.BOT_MAKER),
            error = if (capabilities.load().isEnabled(AssistantCapability.BOT_MAKER)) {
                null
            } else {
                "Bot Maker is off. Turn it on under Advanced → Kai capabilities."
            },
        ),
    )
    val state: StateFlow<BotMakerUi> = mutable.asStateFlow()

    init {
        // When Extra AI tools (MCP) are on, load the servers' tools so they can be picked. Off: nothing is contacted.
        if (mcpTools.enabled()) {
            viewModelScope.launch {
                withContext(Dispatchers.IO) { mcpTools.refreshIfNeeded() }
                tools = toolOptions()
                adoptOldSelections()
                mutable.update { it.copy(tools = tools, toolState = toolStates(it.bots)) }
            }
        }
    }

    private fun toolStates(bots: List<HelperBot>): Map<String, McpResolution> =
        bots.associate { it.id to mcpTools.resolve(it.allowedTools, it.mcpBindings) }

    /**
     * Bots saved before MCP tools were tied to a server: where it is clear which tool was meant, tie it now and
     * save that, so it can never drift to another tool later. The unclear ones stay untied and show "Pick again".
     */
    private fun adoptOldSelections() {
        val bots = mutable.value.bots.map { bot ->
            val adopted = mcpTools.resolve(bot.allowedTools, bot.mcpBindings).adopted
            if (adopted.isEmpty()) bot else bot.copy(mcpBindings = bot.mcpBindings + adopted)
        }
        if (bots == mutable.value.bots) return
        prefs.edit { putString(KEY, HelperBots.encode(bots)) }
        mutable.update { it.copy(bots = bots) }
    }

    /**
     * The boxes to tick when the owner opens [bot] for editing: only tools the bot can really use now. A tool that
     * must be picked again is left unticked, even if a tool with the same name exists, so saving never ties the
     * bot to a replacement the owner did not tick.
     */
    fun tickedFor(bot: HelperBot): List<String> = mcpTools.resolve(bot.allowedTools, bot.mcpBindings).allowed.sorted()

    fun save(id: String?, name: String, instructions: String, allowedTools: Set<String>, model: String): Boolean {
        HelperBots.validate(name, instructions)?.let { problem ->
            mutable.update { it.copy(error = problem, status = null) }
            return false
        }
        // Each ticked MCP tool is tied to the server entry and remote tool that has that name right now.
        val bindings = McpSelections.bind(allowedTools, mcpTools.loadedTools())
        val bot = HelperBot(
            id = id ?: UUID.randomUUID().toString(),
            name = name.trim(),
            instructions = instructions.trim(),
            allowedTools = allowedTools
                .filter { wanted -> tools.any { it.name == wanted } && (!McpBridgedTool.isMcpTool(wanted) || wanted in bindings) }
                .toSet(),
            model = model.trim(),
            mcpBindings = bindings,
        )
        val risky = HelperBots.riskyTools(bot.allowedTools, tools)
        if (risky.isNotEmpty()) {
            mutable.update { it.copy(pendingSave = bot, pendingRiskyTools = risky, error = null) }
            return true
        }
        persist(bot)
        return true
    }

    fun confirmRiskySave() {
        val bot = mutable.value.pendingSave ?: return
        persist(bot)
    }

    fun cancelRiskySave() = mutable.update { it.copy(pendingSave = null, pendingRiskyTools = emptyList()) }

    fun askDelete(bot: HelperBot) = mutable.update { it.copy(pendingDelete = bot) }

    fun cancelDelete() = mutable.update { it.copy(pendingDelete = null) }

    fun confirmDelete() {
        val bot = mutable.value.pendingDelete ?: return
        val bots = mutable.value.bots.filterNot { it.id == bot.id }
        prefs.edit { putString(KEY, HelperBots.encode(bots)) }
        mutable.update { it.copy(bots = bots, toolState = toolStates(bots), pendingDelete = null, status = "Deleted \"${bot.name}\".") }
    }

    /** Hands the bot to Chat, which uses the normal AI provider and the normal approval prompts. */
    fun run(bot: HelperBot) {
        HelperBotHandoff.offer(mutable.value.bots.firstOrNull { it.id == bot.id } ?: bot)
    }

    private fun persist(bot: HelperBot) {
        val bots = HelperBots.upsert(mutable.value.bots, bot)
        prefs.edit { putString(KEY, HelperBots.encode(bots)) }
        mutable.update {
            it.copy(bots = bots, toolState = toolStates(bots), pendingSave = null, pendingRiskyTools = emptyList(), error = null, status = "Saved \"${bot.name}\".")
        }
    }

    private companion object {
        const val KEY = "bots.v1"
    }
}
