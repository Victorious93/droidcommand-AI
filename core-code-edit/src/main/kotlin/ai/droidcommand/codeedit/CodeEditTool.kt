package ai.droidcommand.codeedit

import ai.droidcommand.agent.PermissionCategory
import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolSpec
import java.nio.file.Path

private const val SEARCH_MARKER = "<<<<<<< SEARCH"
private const val DIVIDER_MARKER = "======="
private const val REPLACE_MARKER = ">>>>>>> REPLACE"

/**
 * Parses one or more fenced search/replace blocks out of a single input
 * string. The three marker lines follow git's merge-conflict marker
 * convention (a widely known, purely functional delimiter set that models
 * already produce reliably); the parser and everything downstream of it are
 * original code. Text outside the blocks (an explanation, a code fence line)
 * is ignored.
 *
 * Limitations, by design: the divider is the *first* `=======` line after a
 * SEARCH marker, so search text that itself contains a line consisting only
 * of `=======` cannot be expressed; and a replacement may contain such a line
 * but not a `<<<<<<< SEARCH` line.
 *
 * Throws [IllegalArgumentException] with a human-readable reason (including
 * the 1-based line number of the problem) on malformed input, so
 * [CodeEditTool.execute] can surface it as a [ToolResult.Failure] rather
 * than silently dropping or merging a block.
 */
fun parseEditBlocks(text: String): List<EditBlock> {
    val lines = text.split("\r\n", "\n")
    val blocks = mutableListOf<EditBlock>()
    var i = 0
    while (i < lines.size) {
        if (lines[i].trim() != SEARCH_MARKER) {
            i++
            continue
        }
        val blockStart = i + 1
        i++
        val searchLines = mutableListOf<String>()
        while (i < lines.size && lines[i].trim() != DIVIDER_MARKER) {
            require(lines[i].trim() != SEARCH_MARKER) {
                "Line ${i + 1}: found '$SEARCH_MARKER' before the '$DIVIDER_MARKER' divider of the block starting at line $blockStart"
            }
            searchLines.add(lines[i])
            i++
        }
        require(i < lines.size) { "Unterminated SEARCH block starting at line $blockStart: missing '$DIVIDER_MARKER' divider" }
        i++
        val replaceLines = mutableListOf<String>()
        while (i < lines.size && lines[i].trim() != REPLACE_MARKER) {
            require(lines[i].trim() != SEARCH_MARKER) {
                "Line ${i + 1}: found '$SEARCH_MARKER' before the '$REPLACE_MARKER' marker of the block starting at line $blockStart " +
                    "(is the previous block missing its '$REPLACE_MARKER' line?)"
            }
            replaceLines.add(lines[i])
            i++
        }
        require(i < lines.size) { "Unterminated REPLACE block starting at line $blockStart: missing '$REPLACE_MARKER' marker" }
        i++
        blocks.add(EditBlock(searchLines.joinToString("\n"), replaceLines.joinToString("\n")))
    }
    require(blocks.isNotEmpty()) { "No SEARCH/REPLACE blocks found in 'edits'" }
    return blocks
}

/**
 * Exposes [SearchReplaceEditor] to the agent as a `Tool`. Unlike
 * `core-shell.ShellTool`, this never spawns a process — it's a direct,
 * in-JVM file edit — but it is gated the same way every other
 * `SecurityLevel.SENSITIVE` tool in this repo is, through
 * `core-security.SecureToolExecutor`.
 *
 * Input: `path` (required — the file to edit), `edits` (required — one or
 * more fenced blocks) and `dry_run` (optional, `true`/`false`, default
 * `false` — report and diff without writing):
 * ```
 * <<<<<<< SEARCH
 * exact existing text
 * =======
 * replacement text
 * >>>>>>> REPLACE
 * ```
 * `search` must match the file's current content verbatim, in exactly one
 * place; see [SearchReplaceEditor] for why zero or multiple matches are
 * rejected rather than guessed at. On success the output contains the unified
 * diff (truncated to [maxDiffChars]); on rejection the failure reason lists
 * every block's status.
 */
class CodeEditTool(
    private val editor: SearchReplaceEditor,
    private val maxDiffChars: Int = 20_000,
) : Tool {
    override val spec = ToolSpec(
        name = "edit_file",
        description = "Applies one or more exact search/replace edits to a single file; each search text must match exactly once. " +
            "Returns a unified diff, or a per-block report of unmatched/ambiguous edits. Set dry_run=true to preview.",
        securityLevel = SecurityLevel.SENSITIVE,
        permissionCategory = PermissionCategory.FILES,
    )

    override fun execute(input: Map<String, String>): ToolResult {
        val path = input["path"] ?: return ToolResult.Failure("Missing required input 'path'")
        val editsText = input["edits"] ?: return ToolResult.Failure("Missing required input 'edits'")
        val dryRun = when (val raw = input["dry_run"]?.trim()?.lowercase()) {
            null, "", "false" -> false
            "true" -> true
            else -> return ToolResult.Failure("'dry_run' must be 'true' or 'false', got '$raw'")
        }

        val blocks = try {
            parseEditBlocks(editsText)
        } catch (e: IllegalArgumentException) {
            return ToolResult.Failure(e.message ?: "Could not parse 'edits'")
        }

        return when (val result = editor.apply(Path.of(path), blocks, dryRun)) {
            is EditResult.Applied -> {
                val headline = if (result.written) {
                    "Applied ${result.blocksApplied} edit(s) to '${result.path}'."
                } else {
                    "DRY RUN: no changes written. ${result.blocksApplied} edit(s) would apply to '${result.path}'."
                }
                ToolResult.Success(headline + "\n\n" + truncated(result.diff))
            }
            is EditResult.Rejected -> ToolResult.Failure(result.reason)
        }
    }

    private fun truncated(diff: String): String {
        if (diff.length <= maxDiffChars) return diff
        val cut = diff.lastIndexOf('\n', maxDiffChars).let { if (it <= 0) maxDiffChars else it }
        val hidden = diff.substring(cut).count { it == '\n' }
        return diff.substring(0, cut) + "\n... diff truncated ($hidden more line(s))\n"
    }
}
