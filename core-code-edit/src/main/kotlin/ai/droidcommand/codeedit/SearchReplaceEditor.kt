package ai.droidcommand.codeedit

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * One search/replace block against a single file: [search] must match the
 * file's current content in exactly one place — zero or multiple matches
 * are rejected rather than guessed at, the same fail-closed convention
 * `core-build.WorkspaceManager`'s path-containment checks already apply
 * elsewhere in this repo. There is no fuzzy or line-number-based
 * matching: [search] is matched verbatim, including whitespace, so a
 * caller must quote the real file content, not a paraphrase of it. That
 * is a deliberate scope boundary, not an oversight.
 */
data class EditBlock(val search: String, val replace: String)

/** What happened to one block. Only [MATCHED] is not a problem; a batch is applied only if every block is [MATCHED]. */
enum class BlockStatus { MATCHED, NOT_FOUND, AMBIGUOUS, OVERLAP, EMPTY_SEARCH }

/**
 * The outcome for one block. [index] is 1-based, in the order the caller supplied the blocks.
 * [lines] holds 1-based line numbers: the match location for [BlockStatus.MATCHED], every
 * occurrence (capped) for [BlockStatus.AMBIGUOUS], a near-miss location for [BlockStatus.NOT_FOUND]
 * when one exists, and empty otherwise.
 */
data class BlockReport(val index: Int, val status: BlockStatus, val lines: List<Int>, val detail: String)

sealed class EditResult {
    /**
     * Every block matched exactly once. [diff] is the unified diff (relative to the authorized root);
     * [written] is false only for a dry run, in which case the file on disk is unchanged.
     */
    data class Applied(
        val path: Path,
        val blocksApplied: Int,
        val diff: String = "",
        val reports: List<BlockReport> = emptyList(),
        val written: Boolean = true,
    ) : EditResult()

    /** Nothing was written. [reports] is empty for failures that happen before matching (bad path, unreadable file, ...). */
    data class Rejected(val reason: String, val reports: List<BlockReport> = emptyList()) : EditResult()
}

/**
 * Applies one or more [EditBlock]s to a single file, path-secured against
 * an explicit set of authorized roots. Fail-closed: an empty
 * [authorizedRoots] means nothing is ever writable, mirroring
 * `core-shell.ShellSecurityPolicy`'s "empty means nothing is allowed"
 * default for `allowedExecutables`.
 *
 * Guarantees, each covered by a test:
 * - **Unambiguous or nothing.** Every block is matched against the file's
 *   *original* content (never against another block's output). The batch is
 *   applied only if every block matches exactly once and no two matches
 *   overlap; otherwise the file is left byte-identical and every block's
 *   status is reported, not just the first problem.
 * - **Symlink-safe containment.** Both the roots and the target are resolved
 *   with `toRealPath()`, so a symlink inside a root that points outside it
 *   is rejected.
 * - **No lossy rewrites.** Content must be valid UTF-8 without NUL bytes;
 *   otherwise it is rejected instead of being silently re-encoded. CRLF files
 *   are matched as LF and written back as CRLF; files mixing both are rejected.
 * - **Atomic write.** A temp file in the same directory is moved over the
 *   target (POSIX permissions are copied first), and the write is abandoned
 *   if the file changed on disk since it was read.
 */
class SearchReplaceEditor(
    private val authorizedRoots: List<Path>,
    private val maxFileBytes: Long = 5L * 1024 * 1024,
) {
    fun apply(target: Path, blocks: List<EditBlock>, dryRun: Boolean = false): EditResult {
        if (blocks.isEmpty()) return EditResult.Rejected("No edit blocks supplied")

        val realRoots = authorizedRoots.mapNotNull { runCatching { it.toRealPath() }.getOrNull() }
        if (!Files.exists(target)) return EditResult.Rejected("'$target' does not exist or is not a regular file")
        val real = runCatching { target.toRealPath() }
            .getOrElse { return EditResult.Rejected("Could not resolve '$target': ${it.message}") }
        val root = realRoots.firstOrNull { real.startsWith(it) }
            ?: return EditResult.Rejected("'$target' is outside every authorized root")
        if (!Files.isRegularFile(real)) return EditResult.Rejected("'$target' does not exist or is not a regular file")

        val size = runCatching { Files.size(real) }.getOrElse { return EditResult.Rejected("Could not read '$target': ${it.message}") }
        if (size > maxFileBytes) return EditResult.Rejected("'$target' is $size bytes, over the $maxFileBytes-byte edit limit")
        val modifiedAtRead = runCatching { Files.getLastModifiedTime(real) }.getOrNull()
        val bytes = runCatching { Files.readAllBytes(real) }
            .getOrElse { return EditResult.Rejected("Could not read '$target': ${it.message}") }
        val raw = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            return EditResult.Rejected("'$target' is not valid UTF-8 text; refusing to rewrite it")
        }
        if (raw.indexOf('\u0000') >= 0) return EditResult.Rejected("'$target' contains NUL bytes (binary file); refusing to edit it")

        val crlf = Regex("\r\n").findAll(raw).count()
        val bareLf = raw.count { it == '\n' } - crlf
        if (crlf > 0 && bareLf > 0) {
            return EditResult.Rejected("'$target' mixes CRLF and LF line endings; normalize them before editing")
        }
        val useCrlf = crlf > 0
        val original = if (useCrlf) raw.replace("\r\n", "\n") else raw

        val plans = blocks.mapIndexed { i, block ->
            Plan(i + 1, block.search.replace("\r\n", "\n"), block.replace.replace("\r\n", "\n"))
        }
        val lineStarts = lineStarts(original)
        for (plan in plans) match(plan, original, lineStarts)
        markOverlaps(plans, lineStarts)

        val reports = plans.map { it.toReport() }
        if (plans.any { it.status != BlockStatus.MATCHED }) {
            return EditResult.Rejected(formatRejection(target, reports), reports)
        }

        val updated = StringBuilder(original.length)
        var cursor = 0
        for (plan in plans.sortedBy { it.start }) {
            updated.append(original, cursor, plan.start).append(plan.replace)
            cursor = plan.end
        }
        updated.append(original, cursor, original.length)
        val edited = updated.toString()
        if (edited == original) {
            return EditResult.Rejected("Edit would not change '$target' (every replacement equals the text it replaces)", reports)
        }

        val label = root.relativize(real).toString().replace('\\', '/')
        val diff = UnifiedDiff.generate("a/$label", "b/$label", original, edited)
        if (dryRun) return EditResult.Applied(real, plans.size, diff, reports, written = false)

        val finalText = if (useCrlf) edited.replace("\n", "\r\n") else edited
        writeAtomically(real, finalText.toByteArray(StandardCharsets.UTF_8), size, modifiedAtRead)?.let {
            return EditResult.Rejected(it, reports)
        }
        return EditResult.Applied(real, plans.size, diff, reports, written = true)
    }

    private class Plan(val index: Int, val search: String, val replace: String) {
        var status: BlockStatus = BlockStatus.MATCHED
        var lines: List<Int> = emptyList()
        var detail: String = ""
        var start: Int = -1
        var end: Int = -1

        fun toReport() = BlockReport(index, status, lines, detail)
    }

    private fun match(plan: Plan, content: String, lineStarts: IntArray) {
        if (plan.search.isEmpty()) {
            plan.status = BlockStatus.EMPTY_SEARCH
            plan.detail = "search text is empty; quote the existing text to replace"
            return
        }
        val hits = ArrayList<Int>()
        var from = 0
        while (hits.size <= MAX_SCANNED_HITS) {
            val at = content.indexOf(plan.search, from)
            if (at < 0) break
            hits.add(at)
            from = at + 1 // step by one so overlapping occurrences ("aa" in "aaa") also count as ambiguity
        }
        when (hits.size) {
            0 -> {
                plan.status = BlockStatus.NOT_FOUND
                val near = nearMissLine(content, plan.search)
                plan.lines = listOfNotNull(near)
                plan.detail = "search text not found (0 occurrences)" + when {
                    near != null -> "; a whitespace-insensitive match exists at line $near, so indentation or spacing differs"
                    else -> ""
                }
            }
            1 -> {
                plan.start = hits[0]
                plan.end = hits[0] + plan.search.length
                plan.lines = listOf(lineOf(lineStarts, hits[0]))
                plan.detail = "matched at line ${plan.lines[0]}"
            }
            else -> {
                plan.status = BlockStatus.AMBIGUOUS
                plan.lines = hits.take(MAX_REPORTED_HITS).map { lineOf(lineStarts, it) }
                val count = if (hits.size > MAX_SCANNED_HITS) "more than $MAX_SCANNED_HITS" else "${hits.size}"
                plan.detail = "search text matches $count places (lines ${plan.lines.joinToString(", ")}" +
                    (if (hits.size > MAX_REPORTED_HITS) ", ..." else "") +
                    "); add surrounding lines so it matches exactly once"
            }
        }
    }

    /** Two matched blocks must not share any characters; the later one (by position) is flagged. */
    private fun markOverlaps(plans: List<Plan>, lineStarts: IntArray) {
        val matched = plans.filter { it.status == BlockStatus.MATCHED }.sortedBy { it.start }
        var reach: Plan? = null
        for (plan in matched) {
            val previous = reach
            if (previous != null && plan.start < previous.end) {
                plan.status = BlockStatus.OVERLAP
                plan.lines = listOf(lineOf(lineStarts, plan.start))
                plan.detail = "overlaps block ${previous.index} at line ${lineOf(lineStarts, plan.start)}; merge them into one block"
            } else if (previous == null || plan.end > previous.end) {
                reach = plan
            }
        }
    }

    /** Line number of the first line of [search] found ignoring indentation and runs of whitespace, or null. */
    private fun nearMissLine(content: String, search: String): Int? {
        val wanted = search.split("\n").map(::squeeze).dropWhile { it.isEmpty() }.dropLastWhile { it.isEmpty() }
        if (wanted.isEmpty()) return null
        val have = content.split("\n").map(::squeeze)
        for (i in 0..have.size - wanted.size) {
            var all = true
            for (k in wanted.indices) {
                if (have[i + k] != wanted[k]) {
                    all = false
                    break
                }
            }
            if (all) return i + 1
        }
        return null
    }

    private fun squeeze(line: String) = line.trim().replace(WHITESPACE_RUN, " ")

    private fun formatRejection(target: Path, reports: List<BlockReport>): String {
        val problems = reports.count { it.status != BlockStatus.MATCHED }
        val out = StringBuilder("Edit rejected; no changes were made to '$target' ($problems of ${reports.size} block(s) could not be applied).")
        for (report in reports) {
            out.append("\n  Block ${report.index}: ")
            if (report.status == BlockStatus.MATCHED) {
                out.append("OK, ${report.detail} (not applied because the batch was rejected)")
            } else {
                out.append(report.status.name).append(" - ").append(report.detail)
            }
        }
        return out.toString()
    }

    /** Returns null on success, or a human-readable reason on failure. The original file is untouched on failure. */
    private fun writeAtomically(
        real: Path,
        bytes: ByteArray,
        sizeAtRead: Long,
        modifiedAtRead: java.nio.file.attribute.FileTime?,
    ): String? {
        val changedSinceRead = runCatching {
            Files.size(real) != sizeAtRead || (modifiedAtRead != null && Files.getLastModifiedTime(real) != modifiedAtRead)
        }.getOrDefault(true)
        if (changedSinceRead) return "'$real' changed on disk while the edit was being prepared; nothing was written, retry the edit"

        var temp: Path? = null
        try {
            temp = Files.createTempFile(real.parent, ".dca-edit-", ".tmp")
            Files.write(temp, bytes)
            runCatching { Files.setPosixFilePermissions(temp, Files.getPosixFilePermissions(real)) } // no-op where POSIX perms are unsupported
            try {
                Files.move(temp, real, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, real, StandardCopyOption.REPLACE_EXISTING)
            }
            temp = null
            return null
        } catch (e: IOException) {
            return "Could not write '$real': ${e.message}"
        } finally {
            temp?.let { runCatching { Files.deleteIfExists(it) } }
        }
    }

    private fun lineStarts(text: String): IntArray {
        val starts = ArrayList<Int>()
        starts.add(0)
        for (i in text.indices) if (text[i] == '\n') starts.add(i + 1)
        return starts.toIntArray()
    }

    /** 1-based line containing [offset], by binary search over [lineStarts]. */
    private fun lineOf(lineStarts: IntArray, offset: Int): Int {
        var low = 0
        var high = lineStarts.size - 1
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (lineStarts[mid] <= offset) low = mid else high = mid - 1
        }
        return low + 1
    }

    private companion object {
        const val MAX_SCANNED_HITS = 50
        const val MAX_REPORTED_HITS = 10
        val WHITESPACE_RUN = Regex("\\s+")
    }
}
