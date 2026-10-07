package ai.droidcommand.cli

import ai.droidcommand.agent.Tool
import ai.droidcommand.rootforge.RootForgeCapabilitiesTool
import ai.droidcommand.rootforge.RootForgeListDevicesTool
import ai.droidcommand.rootforge.RootForgeNodeConfig
import ai.droidcommand.rootforge.RootForgeNodeRegistry
import ai.droidcommand.rootforge.parseNodeConfigs
import java.nio.file.Files
import java.nio.file.Path

/**
 * Opt-in RootForge node tools. Off by default: with `DROIDCOMMAND_CLI_ROOTFORGE_NODES_FILE` unset
 * (or unreadable/invalid) no RootForge tool is registered, so DroidCommand AI behaves exactly as it
 * did before and RootForge is never required. The file is `{"nodes":[{node_id, host, port, user,
 * identity_file, host_key}]}` — see `docs/ROOTFORGE_INTEGRATION.md`.
 *
 * An invalid file is reported on stderr and registers nothing; it never falls back to a partial or
 * default node list.
 */
internal fun rootForgeTools(
    nodesFile: String? = System.getenv("DROIDCOMMAND_CLI_ROOTFORGE_NODES_FILE"),
    warn: (String) -> Unit = { System.err.println(it) },
): List<Tool> {
    if (nodesFile.isNullOrBlank()) return emptyList()
    val nodes: List<RootForgeNodeConfig> = try {
        parseNodeConfigs(Files.readString(Path.of(nodesFile)))
    } catch (e: Exception) {
        warn("droidcommand: RootForge nodes file ignored: ${e.message}")
        return emptyList()
    }
    val registry = RootForgeNodeRegistry(nodes)
    return listOf(RootForgeCapabilitiesTool(registry), RootForgeListDevicesTool(registry))
}
