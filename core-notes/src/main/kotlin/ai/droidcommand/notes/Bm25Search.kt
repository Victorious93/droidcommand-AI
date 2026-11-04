package ai.droidcommand.notes

import kotlin.math.ln

data class SearchHit(
    val path: String,
    val title: String,
    val score: Double,
    val snippet: String,
    val tags: Set<String>,
)

/**
 * Okapi BM25 over the vault. Title tokens count triple, tags double, body once.
 * No embeddings — that is a later slice. A query that tokenizes to nothing
 * returns an empty list, not every note.
 */
class Bm25Search {
    private var notes: List<NoteDocument> = emptyList()
    private var avgLen = 1.0
    private var df = emptyMap<String, Int>()
    private var lengths = emptyList<Int>()
    private var fields = emptyList<Map<String, Int>>()

    fun index(notes: List<NoteDocument>) {
        this.notes = notes
        val fieldList = ArrayList<Map<String, Int>>(notes.size)
        val lengthList = ArrayList<Int>(notes.size)
        val documentFrequency = hashMapOf<String, Int>()
        var total = 0
        for (note in notes) {
            val counts = hashMapOf<String, Int>()
            fun add(text: String, weight: Int) {
                for (token in tokenize(text)) {
                    counts[token] = counts.getOrDefault(token, 0) + weight
                }
            }
            add(note.title, 3)
            add(note.stem, 2)
            note.tags.forEach { add(it, 2) }
            add(note.body, 1)
            fieldList += counts
            val len = counts.values.sum().coerceAtLeast(1)
            lengthList += len
            total += len
            counts.keys.forEach { token -> documentFrequency[token] = documentFrequency.getOrDefault(token, 0) + 1 }
        }
        fields = fieldList
        lengths = lengthList
        df = documentFrequency
        avgLen = if (notes.isEmpty()) 1.0 else total.toDouble() / notes.size
    }

    fun search(query: String, limit: Int = 8): List<SearchHit> {
        val terms = tokenize(query).distinct()
        if (terms.isEmpty() || notes.isEmpty()) return emptyList()
        val n = notes.size
        val scored = notes.indices.map { i ->
            var score = 0.0
            val tf = fields[i]
            val dl = lengths[i].toDouble()
            for (term in terms) {
                val freq = tf[term] ?: continue
                val docsWith = df[term] ?: continue
                val idf = ln((n - docsWith + 0.5) / (docsWith + 0.5) + 1.0)
                val denom = freq + K1 * (1 - B + B * dl / avgLen)
                score += idf * (freq * (K1 + 1) / denom)
            }
            i to score
        }.filter { it.second > 0.0 }.sortedByDescending { it.second }.take(limit)
        return scored.map { (i, score) ->
            val note = notes[i]
            SearchHit(
                path = note.relativePath,
                title = note.title,
                score = score,
                snippet = snippet(note.body, terms),
                tags = note.tags,
            )
        }
    }

    companion object {
        private const val K1 = 1.5
        private const val B = 0.75
        private val SPLIT = Regex("[^A-Za-z0-9]+")
        private val STOP = setOf("the", "and", "for", "are", "but", "not", "you", "all", "can", "had", "her", "was", "one", "our", "out", "has", "his")

        fun tokenize(text: String): List<String> =
            text.lowercase().split(SPLIT).filter { it.length >= 2 && it !in STOP }

        fun snippet(body: String, terms: List<String>, max: Int = 160): String {
            val compact = body.replace(Regex("\\s+"), " ").trim()
            if (compact.isEmpty()) return ""
            val lower = compact.lowercase()
            val at = terms.map { lower.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: 0
            val start = (at - 24).coerceAtLeast(0)
            val slice = compact.drop(start).take(max)
            return if (start > 0) "…$slice" else slice
        }
    }
}
