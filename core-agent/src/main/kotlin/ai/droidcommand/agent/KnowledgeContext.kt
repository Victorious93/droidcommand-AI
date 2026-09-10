package ai.droidcommand.agent

/**
 * Formats [entries] as a single block of text suitable for appending to a
 * [ConversationContext] (e.g. `context.append(Role.SYSTEM, it)`), or `null`
 * for an empty list so a caller skips appending a wasted placeholder.
 *
 * Deliberately a standalone formatting function, not wired into
 * [ObjectiveEngine]/[DroidCommandSession]: deciding *when* to retrieve
 * long-term knowledge and *what query* to run for a given turn is an
 * LLM-shaped policy decision that belongs in `core-llm`, which `core-agent`
 * correctly has no dependency on — the same boundary [ConversationStore]'s
 * own doc comment already draws.
 */
fun formatKnowledgeContext(entries: List<KnowledgeEntry>, heading: String = "Relevant knowledge:"): String? {
    if (entries.isEmpty()) return null
    val lines = entries.joinToString("\n") { entry ->
        val tagSuffix = if (entry.tags.isEmpty()) "" else " [${entry.tags.sorted().joinToString(", ")}]"
        "- ${entry.content}$tagSuffix"
    }
    return "$heading\n$lines"
}
