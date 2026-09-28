package ai.droidcommand.hackerai

// Kotlin port of hackeraiETC/lib/ai/subagents/skill-ranker.ts

private fun tokenize(text: String): List<String> =
    text.lowercase().split(Regex("[^a-z]+")).filter { it.length > 1 }

private fun buildIdf(docs: List<List<String>>): Map<String, Double> {
    val n = docs.size
    val df = mutableMapOf<String, Int>()
    for (tokens in docs) {
        for (token in tokens.toSet()) {
            df[token] = (df[token] ?: 0) + 1
        }
    }
    return df.mapValues { (_, count) -> Math.log((n + 1.0) / (count + 1.0)) + 1.0 }
}

private fun tf(tokens: List<String>, token: String): Double =
    tokens.count { it == token }.toDouble() / tokens.size.coerceAtLeast(1)

private fun tfidfVector(tokens: List<String>, idf: Map<String, Double>): Map<String, Double> {
    val vec = mutableMapOf<String, Double>()
    for (token in tokens.toSet()) {
        val score = tf(tokens, token) * (idf[token] ?: 1.0)
        if (score > 0) vec[token] = score
    }
    return vec
}

private fun cosine(a: Map<String, Double>, b: Map<String, Double>): Double {
    var dot = 0.0
    var normA = 0.0
    var normB = 0.0
    for ((token, valA) in a) {
        dot += valA * (b[token] ?: 0.0)
        normA += valA * valA
    }
    for (valB in b.values) normB += valB * valB
    val denom = Math.sqrt(normA) * Math.sqrt(normB)
    return if (denom == 0.0) 0.0 else dot / denom
}

/**
 * Rank skills by TF-IDF cosine similarity to the task description.
 * Returns skills sorted descending by score; ties broken by id.
 */
fun rankSkillsForTask(task: String, candidates: List<SubagentSkill>): List<SubagentSkill> {
    if (candidates.isEmpty()) return emptyList()
    val taskTokens = tokenize(task)
    if (taskTokens.isEmpty()) return candidates.toList()

    val skillDocs = candidates.map { tokenize("${it.name} ${it.description}") }
    val corpus = listOf(taskTokens) + skillDocs
    val idf = buildIdf(corpus)
    val taskVec = tfidfVector(taskTokens, idf)

    return candidates.mapIndexed { i, skill ->
        skill to cosine(taskVec, tfidfVector(skillDocs[i], idf))
    }.sortedWith(compareByDescending<Pair<SubagentSkill, Double>> { it.second }
        .thenBy { it.first.id })
        .map { it.first }
}
