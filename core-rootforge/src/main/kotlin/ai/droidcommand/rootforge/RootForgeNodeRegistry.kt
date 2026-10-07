package ai.droidcommand.rootforge

/**
 * The set of paired nodes, addressed by EXACT node id. There is deliberately no "default node",
 * no prefix matching and no fail-over: a request for node A is never quietly answered by node B,
 * which matters most once operations become writes.
 */
class RootForgeNodeRegistry(
    nodes: List<RootForgeNodeConfig>,
    private val clientFactory: (RootForgeNodeConfig) -> RootForgeClient = { RootForgeClient(it, SshRootForgeTransport(it)) },
) {
    private val byId: Map<String, RootForgeNodeConfig> = nodes.associateBy { it.nodeId }

    init {
        require(byId.size == nodes.size) { "duplicate node_id in registry" }
    }

    val nodeIds: Set<String> get() = byId.keys

    fun clientFor(nodeId: String): RootForgeClient? = byId[nodeId]?.let(clientFactory)
}
