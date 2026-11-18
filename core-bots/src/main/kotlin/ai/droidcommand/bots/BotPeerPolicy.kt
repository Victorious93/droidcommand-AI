package ai.droidcommand.bots

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Who may message whom. A **directed, default-deny allow-list**: bot A being allowed to message bot B says
 * nothing about B messaging A, and a bot with no entries cannot message any other bot. A bot can never be
 * granted a link to itself (that would only be a loop), and an unknown id is never allowed.
 *
 * Immutable; [allow] / [revoke] return a new policy so the owner's UI can hold one value and persist it
 * with [encode]. This type decides *permission* only; delivery limits live in [BotMessageBus].
 */
class BotPeerPolicy private constructor(private val links: Map<String, Set<String>>) {
    fun mayMessage(from: String, to: String): Boolean = from != to && to in links[from].orEmpty()

    /** Bots [from] may message, sorted (for display). */
    fun targetsOf(from: String): List<String> = links[from].orEmpty().sorted()

    fun allow(from: String, to: String): BotPeerPolicy {
        require(from.isNotBlank() && to.isNotBlank()) { "bot ids must not be blank" }
        require(from != to) { "a bot cannot be linked to itself" }
        return BotPeerPolicy(links + (from to (links[from].orEmpty() + to)))
    }

    fun revoke(from: String, to: String): BotPeerPolicy {
        val rest = links[from].orEmpty() - to
        return BotPeerPolicy(if (rest.isEmpty()) links - from else links + (from to rest))
    }

    /** Removes every link into or out of [botId]; call when a bot is deleted so no stale grant survives. */
    fun forget(botId: String): BotPeerPolicy = BotPeerPolicy(
        links.filterKeys { it != botId }
            .mapValues { (_, targets) -> targets - botId }
            .filterValues { it.isNotEmpty() },
    )

    fun encode(): String = JsonArray(
        links.toSortedMap().flatMap { (from, targets) ->
            targets.sorted().map { to ->
                JsonObject(mapOf("from" to JsonPrimitive(from), "to" to JsonPrimitive(to)))
            }
        },
    ).toString()

    companion object {
        val NONE = BotPeerPolicy(emptyMap())

        /** Broken or self-referencing entries are dropped, never turned into a grant. */
        fun decode(text: String?): BotPeerPolicy {
            if (text.isNullOrBlank()) return NONE
            val array = runCatching { Json.parseToJsonElement(text).jsonArray }.getOrNull() ?: return NONE
            return array.fold(NONE) { policy, element ->
                runCatching {
                    val o = element.jsonObject
                    val from = o["from"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val to = o["to"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (from.isBlank() || to.isBlank() || from == to) policy else policy.allow(from, to)
                }.getOrDefault(policy)
            }
        }
    }
}
