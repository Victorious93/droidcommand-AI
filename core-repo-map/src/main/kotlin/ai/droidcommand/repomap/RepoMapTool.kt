package ai.droidcommand.repomap

import ai.droidcommand.agent.PermissionCategory
import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolSpec
import java.nio.file.Path

/**
 * Exposes the repository map to the agent as a `Tool`. `NORMAL`, not `SENSITIVE`: it only reads files
 * inside the authorized roots and never writes or executes anything, the same read-only vs.
 * write-or-execute distinction `core-security` already draws elsewhere.
 *
 * Input, all optional:
 * - `root` — directory to map; defaults to the first authorized root. Must be inside an authorized root.
 * - `task` — what the current work is about; files and symbols related to it are ranked first.
 * - `focus_files` — comma- or newline-separated root-relative paths already known to matter.
 * - `budget_chars` — maximum size of the map (default 6000, allowed 200..200000).
 *
 * The [RepoIndexer] is kept between calls, so a repeat call only re-parses files that changed.
 */
class RepoMapTool(private val authorizedRoots: List<Path>, private val indexer: RepoIndexer = RepoIndexer(authorizedRoots)) : Tool {
    override val spec = ToolSpec(
        name = "repo_map",
        description = "Returns an outline of the codebase's files and their key symbols, ranked by relevance to the given task and focus files",
        securityLevel = SecurityLevel.NORMAL,
        permissionCategory = PermissionCategory.FILES,
    )

    override fun execute(input: Map<String, String>): ToolResult {
        if (authorizedRoots.isEmpty()) return ToolResult.Failure("No authorized root configured for this tool")

        val root = input["root"]?.takeIf { it.isNotBlank() }?.let { Path.of(it) } ?: authorizedRoots.first()
        val budget = when (val raw = input["budget_chars"]?.trim()) {
            null, "" -> DEFAULT_BUDGET
            else -> raw.toIntOrNull()?.takeIf { it in MIN_BUDGET..MAX_BUDGET }
                ?: return ToolResult.Failure("'budget_chars' must be an integer between $MIN_BUDGET and $MAX_BUDGET, got '$raw'")
        }
        val focus = input["focus_files"].orEmpty().split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

        val index = try {
            indexer.index(root)
        } catch (e: IllegalArgumentException) {
            return ToolResult.Failure(e.message ?: "'$root' cannot be mapped")
        }
        if (index.files.isEmpty()) return ToolResult.Success("(no supported source files found under '$root')")

        val ranked = RepoRanker.rank(index, RankingRequest(input["task"].orEmpty(), focus))
        val rendered = RepoMapRenderer.render(ranked, budget, focusFiles = focus)
        val known = index.files.map { it.path }.toSet()
        val notes = buildList {
            if (index.truncated) add("only the first ${indexer.maxFiles} files (by path) were indexed")
            if (index.skipped > 0) add("${index.skipped} file(s) skipped (too large or not UTF-8 text)")
            val unknown = focus.filter { it.replace('\\', '/').removePrefix("./") !in known }
            if (unknown.isNotEmpty()) add("focus_files not found in the index: ${unknown.joinToString(", ")}")
            if (rendered.text.isEmpty()) add("budget_chars=$budget is too small for even one file block")
        }
        return ToolResult.Success(rendered.text + if (notes.isEmpty()) "" else "\n\n[" + notes.joinToString("; ") + "]")
    }

    private companion object {
        const val DEFAULT_BUDGET = 6_000
        const val MIN_BUDGET = 200
        const val MAX_BUDGET = 200_000
    }
}
