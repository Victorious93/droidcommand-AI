package ai.droidcommand.tools.android

/** A compact, indented text summary of a [UiTree] — what an agent reads instead of raw node objects. */
fun renderUiTree(tree: UiTree): String {
    val builder = StringBuilder()
    fun visit(node: UiNode, depth: Int) {
        builder.append("  ".repeat(depth))
        builder.append(node.className ?: "Node")
        node.resourceId?.let { builder.append(" id=$it") }
        node.text?.let { builder.append(" text=\"$it\"") }
        node.contentDescription?.let { builder.append(" desc=\"$it\"") }
        if (node.clickable) builder.append(" [clickable]")
        if (!node.enabled) builder.append(" [disabled]")
        builder.append(" @${node.bounds.left},${node.bounds.top},${node.bounds.right},${node.bounds.bottom}")
        builder.append('\n')
        node.children.forEach { visit(it, depth + 1) }
    }
    visit(tree.root, 0)
    return builder.toString()
}
