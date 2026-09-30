package ai.droidcommand.repomap

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * What the current work is about. [task] is free text (a request, an error message, an identifier);
 * [focusFiles] are root-relative paths the caller already knows matter (open or just-edited files).
 * Either, both or neither may be given; with neither, ranking reflects the repository's structure alone.
 */
data class RankingRequest(val task: String = "", val focusFiles: Set<String> = emptySet())

class RankedSymbol(val definition: Definition, val score: Double)

class FileRank(val path: String, val rank: Double, val symbols: List<RankedSymbol>)

/** Splits identifiers and prose into lower-case words: `parseHTTPRequest_v2` → parse, http, request, v2. */
internal object Words {
    private val STOPWORDS = setOf(
        "the", "and", "for", "with", "from", "that", "this", "into", "add", "fix", "make", "use", "new", "get", "set", "all",
        "how", "not", "are", "can", "should", "when", "then", "update", "change", "implement", "please", "file", "files", "code",
    )
    private val LOWER_UPPER = Regex("([a-z0-9])([A-Z])")
    private val ACRONYM = Regex("([A-Z]+)([A-Z][a-z])")
    private val SEPARATORS = Regex("[^A-Za-z0-9]+")

    fun of(text: String): List<String> =
        text.replace(LOWER_UPPER, "$1 $2").replace(ACRONYM, "$1 $2").split(SEPARATORS).filter { it.isNotEmpty() }.map { it.lowercase() }

    /** Distinct task words worth matching on (length ≥ 3, no filler). */
    fun terms(text: String): Set<String> = of(text).filter { it.length >= 3 && it !in STOPWORDS }.toSet()
}

/**
 * Ranks files, and the symbols inside them, by how central they are to the *current task*.
 *
 * 1. **Graph.** A file A points at a file B when A uses an identifier that B declares. The edge weight for one
 *    identifier is `sqrt(uses) × shape ÷ definers`: repeated use counts with diminishing returns, a
 *    distinctive name (`SecurityPolicyEnforcer`) counts more than a generic one (`run`), and a name declared
 *    in many files is split among them (RM-5's damping). A name declared in more than [MAX_DEFINERS] files is
 *    treated as too ambiguous to be evidence at all.
 * 2. **Personalization.** Every file starts with a small base probability; files named in `focusFiles`, files
 *    whose path contains a task word, and files declaring a symbol whose words contain a task word get more.
 * 3. **PageRank** (power iteration, damping 0.85, teleporting to that personalization vector, dangling files
 *    teleport too) turns that into one score per file: important files, and the files they lean on, rise.
 * 4. **Symbols.** A symbol's score is the share of PageRank flowing into its file through that name, plus a
 *    bonus when its words match the task.
 *
 * Deterministic: no randomness, and every tie is broken by path or line.
 */
object RepoRanker {
    private const val MAX_DEFINERS = 8
    private const val DAMPING = 0.85
    private const val MAX_ITERATIONS = 100
    private const val CONVERGED = 1e-10
    private const val FOCUS_BONUS = 20.0
    private const val PATH_TERM_BONUS = 3.0
    private const val SYMBOL_TERM_BONUS = 2.0
    private const val MAX_SYMBOL_BONUS_PER_FILE = 10.0
    private const val TASK_NAME_BOOST = 5.0

    fun rank(index: RepoIndex, request: RankingRequest = RankingRequest()): List<FileRank> {
        val files = index.files.sortedBy { it.path }
        val n = files.size
        if (n == 0) return emptyList()

        val terms = Words.terms(request.task)
        val rawTaskTokens = request.task.split(Regex("[^A-Za-z0-9_$]+")).filter { it.isNotEmpty() }.map { it.lowercase() }.toSet()

        val definers = HashMap<String, MutableList<Int>>()
        for ((i, file) in files.withIndex()) {
            for (name in file.parsed.definitions.map { it.name }.distinct()) definers.getOrPut(name) { ArrayList() }.add(i)
        }

        // Task-word matches per symbol name, computed once.
        val nameMatches = HashMap<String, Boolean>()
        fun matchesTask(name: String): Boolean = terms.isNotEmpty() && nameMatches.getOrPut(name) {
            name.lowercase() in rawTaskTokens || Words.of(name).any { it in terms }
        }

        val outgoing = Array(n) { HashMap<Int, Double>() }
        val inflows = Array(n) { HashMap<String, MutableList<Pair<Int, Double>>>() } // B -> name -> (A, weight)
        for ((a, file) in files.withIndex()) {
            for ((name, uses) in file.parsed.references) {
                val ds = definers[name] ?: continue
                if (ds.size > MAX_DEFINERS) continue
                val weight = sqrt(uses.toDouble()) * shape(name) * (if (matchesTask(name)) TASK_NAME_BOOST else 1.0) / ds.size
                for (b in ds) {
                    if (b == a) continue
                    outgoing[a].merge(b, weight, Double::plus)
                    inflows[b].getOrPut(name) { ArrayList() }.add(a to weight)
                }
            }
        }
        val outSum = DoubleArray(n) { outgoing[it].values.sum() }

        val personal = DoubleArray(n) { 1.0 }
        val focus = request.focusFiles.map { it.replace('\\', '/').removePrefix("./") }.toSet()
        for ((i, file) in files.withIndex()) {
            if (file.path in focus) personal[i] += FOCUS_BONUS
            if (terms.isNotEmpty()) {
                val pathWords = Words.of(file.path).toSet()
                personal[i] += PATH_TERM_BONUS * terms.count { it in pathWords }
                val symbolBonus = file.parsed.definitions.map { it.name }.distinct().count { matchesTask(it) } * SYMBOL_TERM_BONUS
                personal[i] += minOf(symbolBonus, MAX_SYMBOL_BONUS_PER_FILE)
            }
        }
        val total = personal.sum()
        for (i in 0 until n) personal[i] /= total

        var rank = personal.copyOf()
        for (iteration in 1..MAX_ITERATIONS) {
            val next = DoubleArray(n)
            var dangling = 0.0
            for (a in 0 until n) {
                if (outSum[a] == 0.0) {
                    dangling += rank[a]
                } else {
                    for ((b, w) in outgoing[a]) next[b] += rank[a] * w / outSum[a]
                }
            }
            var delta = 0.0
            for (i in 0 until n) {
                next[i] = (1 - DAMPING) * personal[i] + DAMPING * (next[i] + dangling * personal[i])
                delta += abs(next[i] - rank[i])
            }
            rank = next
            if (delta < CONVERGED) break
        }

        val result = files.mapIndexed { b, file ->
            val seen = HashSet<String>()
            val symbols = file.parsed.definitions.filter { seen.add(it.name) }.map { definition ->
                val flow = inflows[b][definition.name]?.sumOf { (a, w) -> rank[a] * w / outSum[a] } ?: 0.0
                val bonus = if (matchesTask(definition.name)) SYMBOL_TERM_BONUS * rank[b] else 0.0
                RankedSymbol(definition, flow + bonus)
            }.sortedWith(compareByDescending<RankedSymbol> { it.score }.thenBy { it.definition.line })
            FileRank(file.path, rank[b], symbols)
        }
        return result.sortedWith(compareByDescending<FileRank> { it.rank }.thenBy { it.path })
    }

    /** Distinctive names are better evidence of a real dependency than short generic ones. */
    private fun shape(name: String): Double = when {
        name.startsWith("_") -> 0.1
        name.length >= 8 && (name.contains('_') || name.drop(1).any { it.isUpperCase() }) -> 2.0
        name.length < 3 -> 0.2
        else -> 1.0
    }
}
