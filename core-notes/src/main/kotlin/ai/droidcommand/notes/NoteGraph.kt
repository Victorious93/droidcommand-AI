package ai.droidcommand.notes

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

data class GraphNode(
    val path: String,
    val title: String,
    val tags: Set<String> = emptySet(),
)

data class GraphEdge(
    val from: String,
    val to: String,
)

data class GraphPoint(
    val path: String,
    val x: Float,
    val y: Float,
)

/**
 * Notes as nodes and `[[wikilinks]]` as directed edges. This is a first-slice
 * graph (circular layout, no force simulation) built from [NoteIndexSnapshot].
 */
data class NoteGraph(
    val nodes: List<GraphNode>,
    val edges: List<GraphEdge>,
) {
    fun layout(width: Float, height: Float): List<GraphPoint> {
        if (nodes.isEmpty() || width <= 0f || height <= 0f) return emptyList()
        val cx = width / 2f
        val cy = height / 2f
        if (nodes.size == 1) return listOf(GraphPoint(nodes[0].path, cx, cy))
        val radius = min(width, height) / 2f - 24f
        return nodes.mapIndexed { index, node ->
            val angle = (2.0 * PI * index / nodes.size) - PI / 2.0
            GraphPoint(
                path = node.path,
                x = cx + (radius * cos(angle)).toFloat(),
                y = cy + (radius * sin(angle)).toFloat(),
            )
        }
    }

    fun hit(width: Float, height: Float, x: Float, y: Float, radius: Float = 32f): String? {
        val points = layout(width, height)
        var best: GraphPoint? = null
        var bestDist = radius * radius
        for (point in points) {
            val dx = point.x - x
            val dy = point.y - y
            val dist = dx * dx + dy * dy
            if (dist <= bestDist) {
                best = point
                bestDist = dist
            }
        }
        return best?.path
    }

    companion object {
        fun from(snapshot: NoteIndexSnapshot): NoteGraph {
            val nodes = snapshot.notes.map { note ->
                GraphNode(path = note.relativePath, title = note.title, tags = note.tags)
            }
            val seen = linkedSetOf<Pair<String, String>>()
            val edges = mutableListOf<GraphEdge>()
            for (note in snapshot.notes) {
                for (link in note.links) {
                    val target = snapshot.resolveLink(link.target) ?: continue
                    val pair = note.relativePath to target.relativePath
                    if (seen.add(pair)) {
                        edges += GraphEdge(from = note.relativePath, to = target.relativePath)
                    }
                }
            }
            return NoteGraph(nodes = nodes, edges = edges)
        }
    }
}
