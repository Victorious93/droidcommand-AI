package ai.droidcommand.git

import ai.droidcommand.agent.PermissionCategory
import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolSpec

/**
 * Exposes [GitCheckpointer.undo] and [GitCheckpointer.checkpoints] to the agent.
 *
 * Input: `action` (optional, `undo` (default) or `list`) and `checkpoint` (optional commit id, only
 * meaningful for `undo`; omitted means the most recent checkpoint that has not been undone).
 *
 * `SENSITIVE` (it writes to the repository) and `TERMINAL`-category (it runs `git`), so it is gated by
 * `core-security.SecureToolExecutor` like every other tool. An undo that would overwrite anyone's work
 * comes back as a [ToolResult.Failure] describing why nothing was changed.
 */
class GitUndoTool(private val checkpointer: GitCheckpointer) : Tool {
    override val spec = ToolSpec(
        name = "git_undo",
        description = "Undoes an agent checkpoint commit (default: the latest not yet undone) by restoring only the files it changed, " +
            "as a new commit; refuses if those files have uncommitted or later changes. action=list shows recent checkpoints.",
        securityLevel = SecurityLevel.SENSITIVE,
        permissionCategory = PermissionCategory.TERMINAL,
    )

    override fun execute(input: Map<String, String>): ToolResult {
        return when (val action = input["action"]?.trim()?.lowercase().orEmpty().ifEmpty { "undo" }) {
            "list" -> when (val listed = checkpointer.checkpoints()) {
                is CheckpointList.Failed -> ToolResult.Failure(listed.reason)
                is CheckpointList.Listed -> ToolResult.Success(
                    if (listed.items.isEmpty()) {
                        "No agent checkpoints found."
                    } else {
                        listed.items.joinToString("\n") { "${it.commit.take(8)}  ${it.subject}" + if (it.undone) "  (undone)" else "" }
                    },
                )
            }
            "undo" -> when (val result = checkpointer.undo(input["checkpoint"]?.takeIf { it.isNotBlank() })) {
                is UndoResult.Undone -> ToolResult.Success(
                    "Undid checkpoint ${result.checkpoint.take(8)} in new commit ${result.undoCommit.take(8)}; restored: ${result.paths.joinToString(", ")}",
                )
                is UndoResult.NothingToUndo -> ToolResult.Success("No agent checkpoint left to undo.")
                is UndoResult.Refused -> ToolResult.Failure("Undo refused: ${result.reason}")
                is UndoResult.Failed -> ToolResult.Failure("Undo failed: ${result.reason}")
            }
            else -> ToolResult.Failure("'action' must be 'undo' or 'list', got '$action'")
        }
    }
}
