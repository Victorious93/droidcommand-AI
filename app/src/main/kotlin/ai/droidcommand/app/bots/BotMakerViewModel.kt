package ai.droidcommand.app.bots

import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.ToolRegistry
import ai.droidcommand.config.AssistantCapability
import ai.droidcommand.config.AssistantCapabilitySettingsStore
import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
)

/** Helper bots are kept in this app's private storage on the phone; nothing is uploaded. */
@HiltViewModel
class BotMakerViewModel @Inject constructor(
    @ApplicationContext context: Context,
    registry: ToolRegistry,
    private val capabilities: AssistantCapabilitySettingsStore,
) : ViewModel() {
    private val prefs = context.getSharedPreferences("helper_bots", Context.MODE_PRIVATE)
    private val tools = registry.list().map {
        BotToolOption(
            name = it.name,
            description = it.description,
            risky = it.requiresConfirmation || it.requiresRoot || it.securityLevel != SecurityLevel.NORMAL,
        )
    }.sortedWith(compareBy({ it.risky }, { it.name }))

    private val mutable = MutableStateFlow(
        BotMakerUi(
            bots = HelperBots.decode(prefs.getString(KEY, null)),
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

    fun save(id: String?, name: String, instructions: String, allowedTools: Set<String>, model: String): Boolean {
        HelperBots.validate(name, instructions)?.let { problem ->
            mutable.update { it.copy(error = problem, status = null) }
            return false
        }
        val bot = HelperBot(
            id = id ?: UUID.randomUUID().toString(),
            name = name.trim(),
            instructions = instructions.trim(),
            allowedTools = allowedTools.filter { wanted -> tools.any { it.name == wanted } }.toSet(),
            model = model.trim(),
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
        mutable.update { it.copy(bots = bots, pendingDelete = null, status = "Deleted \"${bot.name}\".") }
    }

    /** Hands the bot to Chat, which uses the normal AI provider and the normal approval prompts. */
    fun run(bot: HelperBot) {
        HelperBotHandoff.offer(bot)
    }

    private fun persist(bot: HelperBot) {
        val bots = HelperBots.upsert(mutable.value.bots, bot)
        prefs.edit { putString(KEY, HelperBots.encode(bots)) }
        mutable.update {
            it.copy(bots = bots, pendingSave = null, pendingRiskyTools = emptyList(), error = null, status = "Saved \"${bot.name}\".")
        }
    }

    private companion object {
        const val KEY = "bots.v1"
    }
}
