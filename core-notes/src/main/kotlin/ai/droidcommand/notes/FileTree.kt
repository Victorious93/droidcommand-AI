package ai.droidcommand.notes

/**
 * One folder or markdown note in the vault tree. Folders list their children;
 * notes have none. Hidden names are already omitted by [NoteVault.list].
 */
data class FileTreeNode(
    val name: String,
    val relativePath: String,
    val folder: Boolean,
    val children: List<FileTreeNode> = emptyList(),
)

object FileTree {
    fun build(vault: NoteVault, folder: String = ""): List<FileTreeNode> {
        return vault.list(folder).map { entry ->
            FileTreeNode(
                name = entry.name,
                relativePath = entry.relativePath,
                folder = entry.folder,
                children = if (entry.folder) build(vault, entry.relativePath) else emptyList(),
            )
        }
    }

    fun folderPaths(nodes: List<FileTreeNode>): List<String> {
        val out = mutableListOf<String>()
        fun walk(node: FileTreeNode) {
            if (!node.folder) return
            out += node.relativePath
            node.children.forEach(::walk)
        }
        nodes.forEach(::walk)
        return out
    }

    /** Depth-first rows that a file-tree UI can render, honouring which folders are open. */
    fun visible(nodes: List<FileTreeNode>, expanded: Set<String>): List<Pair<FileTreeNode, Int>> {
        val out = mutableListOf<Pair<FileTreeNode, Int>>()
        fun walk(node: FileTreeNode, depth: Int) {
            out += node to depth
            if (node.folder && node.relativePath in expanded) {
                node.children.forEach { walk(it, depth + 1) }
            }
        }
        nodes.forEach { walk(it, 0) }
        return out
    }
}
