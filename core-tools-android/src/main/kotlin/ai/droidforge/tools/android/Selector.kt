package ai.droidforge.tools.android

/** Ways to pick out node(s) in a captured [UiTree]. [And] composes any number of the others. */
sealed class Selector {
    data class ByText(val text: String, val exact: Boolean = true) : Selector()
    data class ByResourceId(val resourceId: String) : Selector()
    data class ByContentDescription(val description: String, val exact: Boolean = true) : Selector()
    data class ByClassName(val className: String) : Selector()
    data class And(val selectors: List<Selector>) : Selector()
}

private fun matches(node: UiNode, selector: Selector): Boolean = when (selector) {
    is Selector.ByText -> if (selector.exact) {
        node.text == selector.text
    } else {
        node.text?.contains(selector.text, ignoreCase = true) == true
    }

    is Selector.ByResourceId -> node.resourceId == selector.resourceId

    is Selector.ByContentDescription -> if (selector.exact) {
        node.contentDescription == selector.description
    } else {
        node.contentDescription?.contains(selector.description, ignoreCase = true) == true
    }

    is Selector.ByClassName -> node.className == selector.className

    is Selector.And -> selector.selectors.all { matches(node, it) }
}

/** All nodes matching [selector], in tree (depth-first, pre-order) order. */
fun UiTree.findAll(selector: Selector): List<UiNode> {
    val results = mutableListOf<UiNode>()
    fun visit(node: UiNode) {
        if (matches(node, selector)) results += node
        node.children.forEach(::visit)
    }
    visit(root)
    return results
}

/** The first node matching [selector] in tree order, or null if none matches. */
fun UiTree.findFirst(selector: Selector): UiNode? = findAll(selector).firstOrNull()
