package ai.droidcommand.git

import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolSpec

/**
 * Wraps any file-changing [Tool] so that, when [enabled], a successful run is recorded as a git
 * checkpoint of exactly the paths [pathsOf] names (default: the `path` input, which is what
 * `core-code-edit`'s `edit_file` takes).
 *
 * - **Opt-in.** With [enabled] = false (the default) this is a pure pass-through; nothing touches git.
 * - **Never changes the wrapped result.** The wrapped tool's [ToolResult] is returned unchanged except
 *   that a one-line checkpoint note is appended to the output of a [ToolResult.Success] or
 *   [ToolResult.Partial]; a [ToolResult.Failure] or [ToolResult.Unexpected] is returned untouched and
 *   nothing is committed.
 * - **Never blocks the edit.** If checkpointing is skipped or fails (see [BeginResult.Skipped]), the
 *   edit has still happened and the note says why it was not recorded.
 * - [spec] is the wrapped tool's own, so registration, approval level and permission category are
 *   unchanged: wrapping adds no privilege. (The extra `git` process is authorized separately, by the
 *   `ShellSecurityPolicy` the [GitCheckpointer] was built with.)
 */
class CheckpointingTool(
    private val delegate: Tool,
    private val checkpointer: GitCheckpointer,
    private val enabled: Boolean = false,
    private val pathsOf: (Map<String, String>) -> List<String> = { input -> listOfNotNull(input["path"]) },
    private val messageOf: (ToolSpec, List<String>) -> String = { spec, paths -> "agent: ${spec.name} ${paths.joinToString(", ")}" },
) : Tool {
    override val spec: ToolSpec get() = delegate.spec

    override fun execute(input: Map<String, String>): ToolResult {
        if (!enabled) return delegate.execute(input)
        val paths = pathsOf(input)
        if (paths.isEmpty()) return delegate.execute(input)

        // Read-only, so safe before the edit. An unexpected bug in checkpointing must never block the edit itself.
        val begin = try {
            checkpointer.begin(paths)
        } catch (e: RuntimeException) {
            BeginResult.Skipped("checkpointing failed unexpectedly (${e::class.simpleName}: ${e.message})")
        }
        val result = delegate.execute(input)
        val note = when (result) {
            is ToolResult.Success, is ToolResult.Partial -> try {
                noteFor(begin)
            } catch (e: RuntimeException) {
                "[checkpoint failed unexpectedly, the edit itself is in place: ${e::class.simpleName}: ${e.message}]"
            }
            else -> return result
        }
        return when (result) {
            is ToolResult.Success -> result.copy(output = result.output + "\n\n" + note)
            is ToolResult.Partial -> result.copy(output = result.output + "\n\n" + note)
            else -> result
        }
    }

    private fun noteFor(begin: BeginResult): String = when (begin) {
        is BeginResult.Skipped -> "[checkpoint skipped: ${begin.reason}]"
        is BeginResult.Ready -> when (val committed = begin.commit(messageOf(delegate.spec, begin.paths))) {
            is CheckpointResult.Committed -> "[checkpoint ${committed.checkpoint.commit.take(8)} recorded; undo with the git_undo tool]"
            is CheckpointResult.NoChanges -> "[checkpoint skipped: the file content did not change]"
            is CheckpointResult.Failed -> "[checkpoint failed, the edit itself is in place: ${committed.reason}]"
        }
    }
}
