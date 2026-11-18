package ai.droidcommand.app.ui.models

import ai.droidcommand.app.ui.components.PillTone
import ai.droidcommand.llm.local.RamFitVerdict
import java.util.Locale

/** The three places a model can come from. One tab each, so the screen is no longer one long scroll. */
enum class ModelsTab(val label: String) {
    PHONE("On this phone"),
    CATALOG("Catalog"),
    SEARCH("Search"),
}

/**
 * Wording and ordering for the Models screen. Pure functions, so the decisions (what is listed first, how a size or
 * a download count reads) are JVM-tested instead of being buried in composables.
 */
internal object ModelsPresentation {
    private const val KB = 1024L
    private const val MB = KB * KB
    private const val GB = MB * KB

    /**
     * Search terms the owner can tap instead of typing. They are only terms: nothing claims a result exists. Llama and
     * Gemma are left out on purpose, because their Hub licenses are not on the free list the search is limited to
     * (HuggingFaceLicense), so those searches would come back empty.
     */
    val SEARCH_SUGGESTIONS = listOf("qwen", "smollm", "phi", "tinyllama", "mistral")

    /** "468 MB" below one GiB, "1.2 GB" above. Matches how the rest of the screen counted (binary megabytes). */
    fun sizeLabel(bytes: Long): String {
        val safe = bytes.coerceAtLeast(0L)
        return if (safe < GB) {
            "${safe / MB} MB"
        } else {
            String.format(Locale.ROOT, "%.1f GB", safe.toDouble() / GB)
        }
    }

    /** "950", "1.2K", "12K", "3.4M". Used for download counts on search results. */
    fun countLabel(n: Long): String {
        val safe = n.coerceAtLeast(0L)
        fun scaled(value: Double, suffix: String): String {
            val text = String.format(Locale.ROOT, if (value >= 10) "%.0f" else "%.1f", value)
            return text.removeSuffix(".0") + suffix
        }
        return when {
            safe < 1_000L -> safe.toString()
            safe < 999_500L -> scaled(safe / 1_000.0, "K")
            safe < 999_500_000L -> scaled(safe / 1_000_000.0, "M")
            else -> scaled(safe / 1_000_000_000.0, "B")
        }
    }

    /** Models that fit this phone first, then tight ones, then those that cannot run; smaller first inside each group. */
    fun <T> orderedByFit(rows: List<T>, verdict: (T) -> RamFitVerdict, sizeBytes: (T) -> Long): List<T> =
        rows.sortedWith(compareBy<T>({ verdict(it).ordinal }, { sizeBytes(it) }))

    /** The pill on a model: plain words first, colour second. */
    fun fitText(verdict: RamFitVerdict): String = when (verdict) {
        RamFitVerdict.FITS -> "Fits this phone"
        RamFitVerdict.TIGHT -> "RAM is tight"
        RamFitVerdict.TOO_BIG -> "Too big"
    }

    fun fitTone(verdict: RamFitVerdict): PillTone = when (verdict) {
        RamFitVerdict.FITS -> PillTone.Success
        RamFitVerdict.TIGHT -> PillTone.Warning
        RamFitVerdict.TOO_BIG -> PillTone.Error
    }

    /** "Qwen/Qwen2.5-0.5B-Instruct-GGUF" -> ("Qwen", "Qwen2.5-0.5B-Instruct-GGUF"). A repo with no owner has a null owner. */
    fun splitRepoId(id: String): Pair<String?, String> {
        val slash = id.indexOf('/')
        return if (slash <= 0 || slash == id.lastIndex) null to id else id.substring(0, slash) to id.substring(slash + 1)
    }

    /**
     * Q4_K_M is the size/quality middle the community usually starts with, so it gets a quiet "Common pick" tag.
     * It is a hint for someone who does not know what the quant names mean, not a measurement made by this app.
     */
    fun isCommonPick(quant: String?): Boolean = quant.equals("Q4_K_M", ignoreCase = true)

    /** What the search tab shows for a trimmed query; blank means nothing to search yet. */
    fun searchable(query: String): String? = query.trim().takeIf { it.isNotEmpty() }
}
