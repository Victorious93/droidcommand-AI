package ai.droidcommand.app.bots

import ai.droidcommand.skills.mcp.McpToolBinding
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A small helper bot the user makes on the Bot maker screen. Saved only on this phone. */
data class HelperBot(
    val id: String,
    val name: String,
    val instructions: String,
    val allowedTools: Set<String> = emptySet(),
    /** Model name for the current AI provider; blank keeps whatever model Chat already uses. */
    val model: String = "",
    /**
     * For each MCP tool in [allowedTools]: the server entry and exact remote tool the owner ticked. An MCP tool
     * without one here was saved by an older version; see [ai.droidcommand.skills.mcp.McpSelections].
     */
    val mcpBindings: Map<String, McpToolBinding> = emptyMap(),
)

/** What the Bot maker needs to know about one tool. Built from the app's real tool list. */
data class BotToolOption(val name: String, val description: String, val risky: Boolean)

object HelperBots {
    const val MAX_NAME = 60
    const val MAX_INSTRUCTIONS = 4_000

    fun validate(name: String, instructions: String): String? = when {
        name.isBlank() -> "Give the bot a name."
        name.length > MAX_NAME -> "Keep the name under $MAX_NAME characters."
        instructions.isBlank() -> "Tell the bot what to do."
        instructions.length > MAX_INSTRUCTIONS -> "Keep the instructions under $MAX_INSTRUCTIONS characters."
        else -> null
    }

    /** Tools in [selected] that can change the phone or need approval; saving these asks first. */
    fun riskyTools(selected: Set<String>, options: List<BotToolOption>): List<String> =
        options.filter { it.risky && it.name in selected }.map { it.name }.sorted()

    /**
     * The system text added to every message while the bot is running in Chat. Named tools are also enforced
     * by a hard allow-list in Chat ([allowedNow]: the saved list, minus MCP tools that are not the ones the owner
     * ticked or are not loaded); the bot never gets a way around the app's approval prompts.
     */
    fun systemPrompt(bot: HelperBot, allowedNow: Set<String> = bot.allowedTools): String = buildString {
        append("You are \"").append(bot.name.trim()).append("\", a helper bot inside DroidCommand.\n")
        append(bot.instructions.trim()).append('\n')
        if (allowedNow.isEmpty()) {
            append("You may not use any device tools; answer in text only.")
        } else {
            append("You may only use these device tools: ")
            append(allowedNow.sorted().joinToString(", "))
            append(". Tools not on this list are refused. Any tool that can change the phone must be approved by the user first; never claim you ran one yourself.")
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(bots: List<HelperBot>): String = JsonArray(
        bots.map { bot ->
            JsonObject(
                mapOf(
                    "id" to JsonPrimitive(bot.id),
                    "name" to JsonPrimitive(bot.name),
                    "instructions" to JsonPrimitive(bot.instructions),
                    "tools" to JsonArray(bot.allowedTools.sorted().map { JsonPrimitive(it) }),
                    "model" to JsonPrimitive(bot.model),
                    "mcp" to JsonArray(
                        bot.mcpBindings.filterKeys { it in bot.allowedTools }.toSortedMap().map { (tool, binding) ->
                            JsonObject(
                                mapOf(
                                    "tool" to JsonPrimitive(tool),
                                    "server" to JsonPrimitive(binding.serverInstance),
                                    "remote" to JsonPrimitive(binding.remoteName),
                                ),
                            )
                        },
                    ),
                ),
            )
        },
    ).toString()

    /** Bad or partly bad saved data never crashes the app: broken entries are skipped. */
    fun decode(text: String?): List<HelperBot> {
        if (text.isNullOrBlank()) return emptyList()
        val array = runCatching { json.parseToJsonElement(text).jsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { element ->
            runCatching {
                val o = element.jsonObject
                val id = o["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val name = o["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (id.isBlank() || name.isBlank()) return@runCatching null
                val tools = o["tools"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet().orEmpty()
                HelperBot(
                    id = id,
                    name = name,
                    instructions = o["instructions"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    allowedTools = tools,
                    model = o["model"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    mcpBindings = decodeBindings(o["mcp"], tools),
                )
            }.getOrNull()
        }
    }

    /** A broken binding is dropped, which leaves that tool needing to be picked again (never guessed). */
    private fun decodeBindings(element: JsonElement?, tools: Set<String>): Map<String, McpToolBinding> {
        val array = element as? JsonArray ?: return emptyMap()
        return array.mapNotNull { entry ->
            val o = entry as? JsonObject ?: return@mapNotNull null
            val tool = (o["tool"] as? JsonPrimitive)?.contentOrNull?.takeIf { it in tools } ?: return@mapNotNull null
            val server = (o["server"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val remote = (o["remote"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            tool to McpToolBinding(server, remote)
        }.toMap()
    }

    fun upsert(bots: List<HelperBot>, bot: HelperBot): List<HelperBot> =
        if (bots.any { it.id == bot.id }) bots.map { if (it.id == bot.id) bot else it } else bots + bot
}

/** A bot the user tapped "Run" on, waiting for the Chat screen to pick it up. */
object HelperBotHandoff {
    @Volatile private var pending: HelperBot? = null

    fun offer(bot: HelperBot) {
        pending = bot
    }

    fun consume(): HelperBot? = pending.also { pending = null }
}
