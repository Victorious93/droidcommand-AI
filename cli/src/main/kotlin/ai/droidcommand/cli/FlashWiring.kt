package ai.droidcommand.cli

import ai.droidcommand.agent.Tool
import ai.droidcommand.flash.FlashNodeConfig
import ai.droidcommand.flash.FlashNodeRegistry
import ai.droidcommand.flash.flashTools
import ai.droidcommand.flash.parseFlashNodeConfigs
import java.nio.file.Files
import java.nio.file.Path

/**
 * Opt-in Victorious Flash node tools. Off by default: with `DROIDCOMMAND_CLI_FLASH_NODES_FILE` unset
 * (or unreadable/invalid) no flash tool is registered. The file is
 * `{"nodes":[{node_id:"vf-…", host, port, user, identity_file, host_key}]}`; see
 * `docs/FLASH_INTEGRATION.md`. An invalid file is reported on stderr and registers nothing.
 */
internal fun flashNodeTools(
    nodesFile: String? = System.getenv("DROIDCOMMAND_CLI_FLASH_NODES_FILE"),
    warn: (String) -> Unit = { System.err.println(it) },
    registryFactory: (List<FlashNodeConfig>) -> FlashNodeRegistry = { FlashNodeRegistry(it) },
): List<Tool> {
    if (nodesFile.isNullOrBlank()) return emptyList()
    val nodes = try {
        parseFlashNodeConfigs(Files.readString(Path.of(nodesFile)))
    } catch (e: Exception) {
        warn("droidcommand: flash nodes file ignored: ${e.message}")
        return emptyList()
    }
    return flashTools(registryFactory(nodes))
}
