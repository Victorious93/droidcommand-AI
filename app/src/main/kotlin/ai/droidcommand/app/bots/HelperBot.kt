package ai.droidcommand.app.bots

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
     * The system text added to every message while the bot is running in Chat. Tools are named so the AI
     * knows its limits; the bot never gets a way around the app's approval prompts.
     */
    fun systemPrompt(bot: HelperBot): String = buildString {
        append("You are \"").append(bot.name.trim()).append("\", a helper bot inside DroidCommand.\n")
        append(bot.instructions.trim()).append('\n')
        if (bot.allowedTools.isEmpty()) {
            append("You may not use any device tools; answer in text only.")
        } else {
            append("You may only suggest these device tools: ")
            append(bot.allowedTools.sorted().joinToString(", "))
            append(". Any tool that can change the phone must be approved by the user first; never claim you ran one yourself.")
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
                HelperBot(
                    id = id,
                    name = name,
                    instructions = o["instructions"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    allowedTools = o["tools"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet().orEmpty(),
                    model = o["model"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                )
            }.getOrNull()
        }
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
