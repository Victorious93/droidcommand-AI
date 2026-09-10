package ai.droidcommand.tools.android

import ai.droidcommand.agent.ContextContribution
import ai.droidcommand.agent.ContextKind
import ai.droidcommand.agent.ContextProvider
import ai.droidcommand.agent.Task

/**
 * Composes [DeviceController.listDirectory] into one
 * `core-agent.ContextKind.FILES` [ContextContribution] (CAP-003, P0.3's
 * "Local Files" step) — the same cross-module adapter pattern
 * `DeviceContextProvider` already establishes.
 *
 * **Deciding which path is relevant to a task stays a caller policy
 * decision**, taken as [path] rather than a fixed directory: this
 * provider is a mechanical formatter only, the same boundary
 * `KnowledgeContextProvider`'s own `query` function already draws.
 * Returning `null` from [path] means "nothing to contribute for this
 * task," the same [ContextProvider] convention every other provider uses.
 *
 * **Scoped to directory listings, not file contents, deliberately:**
 * this reports what files exist (name/kind/size), never calls
 * [DeviceController.readFile] to inline file contents into a
 * token-budgeted snapshot — doing that with no summarization/truncation
 * strategy would be an unbounded-size risk this slice does not take on.
 */
class FilesContextProvider(
    private val controller: DeviceController,
    private val path: (Task?) -> String?,
) : ContextProvider {
    override fun provide(task: Task?): ContextContribution? {
        val directory = path(task) ?: return null
        val description = when (val result = controller.listDirectory(directory)) {
            is FileListResult.Success -> describeEntries(result.entries)
            is FileListResult.Failure -> "unavailable (${result.reason})"
        }
        return ContextContribution(ContextKind.FILES, "files:$directory", "$directory:\n$description")
    }

    private fun describeEntries(entries: List<FileEntry>): String {
        if (entries.isEmpty()) return "(empty)"
        return entries.joinToString("\n") { entry ->
            val kind = if (entry.isDirectory) "dir" else "file"
            val size = entry.sizeBytes?.let { " (${it}B)" }.orEmpty()
            "- ${entry.name} [$kind]$size"
        }
    }
}
