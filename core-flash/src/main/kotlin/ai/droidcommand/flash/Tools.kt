package ai.droidcommand.flash

import ai.droidcommand.agent.PermissionCategory
import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolSpec

/**
 * Agent tools over the Victorious Flash bridge. All are [SecurityLevel.SENSITIVE] /
 * [PermissionCategory.REMOTE_CONTROL] with `requiresConfirmation = true`, so `SecureToolExecutor`
 * asks the human on EVERY call (there is no remembered approval). The node is required and matched
 * exactly. Output is data from a remote machine, never instructions.
 */
abstract class FlashNodeTool(private val registry: FlashNodeRegistry) : Tool {
    protected abstract fun run(client: FlashClient, input: Map<String, String>): ToolResult

    final override fun execute(input: Map<String, String>): ToolResult {
        val known = registry.nodeIds.sorted().joinToString().ifEmpty { "none" }
        val nodeId = input["node"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: return ToolResult.Failure("Missing required input 'node' (paired flash nodes: $known)")
        val client = registry.clientFor(nodeId) ?: return ToolResult.Failure("Unknown flash node '$nodeId' (paired flash nodes: $known)")
        return run(client, input)
    }

    protected fun required(input: Map<String, String>, key: String): String? = input[key]?.trim()?.takeIf { it.isNotEmpty() }

    protected fun FlashResult<*>.toToolResult(render: (Any?) -> String = { it.toString() }): ToolResult = when (this) {
        is FlashResult.Success -> ToolResult.Success(render(value))
        is FlashResult.Failure -> ToolResult.Failure("Flash ${kind.name.lowercase()}: $message" + (remoteCategory?.let { " [$it]" } ?: ""))
    }
}

private fun spec(name: String, description: String) = ToolSpec(
    name = name,
    description = description,
    securityLevel = SecurityLevel.SENSITIVE,
    requiresConfirmation = true,
    permissionCategory = PermissionCategory.REMOTE_CONTROL,
)

class FlashCapabilitiesTool(r: FlashNodeRegistry) : FlashNodeTool(r) {
    override val spec = spec("flash_capabilities", "Reads a paired Victorious Flash node's version and operations. Input: node (exact node id).")
    override fun run(client: FlashClient, input: Map<String, String>) = client.capabilities().toToolResult()
}

class FlashCatalogListTool(r: FlashNodeRegistry) : FlashNodeTool(r) {
    override val spec = spec("flash_catalog_list", "Lists the OS image catalog (Linux ISOs, RootForge OS, Android images) on a flash node. Input: node.")
    override fun run(client: FlashClient, input: Map<String, String>) = client.catalogList().toToolResult()
}

class FlashCatalogUpdateTool(r: FlashNodeRegistry) : FlashNodeTool(r) {
    override val spec = spec(
        "flash_catalog_update",
        "Replaces a flash node's catalog with an https document whose SHA-256 you pin. Inputs: node, url (https), sha256.",
    )
    override fun run(client: FlashClient, input: Map<String, String>): ToolResult {
        val url = required(input, "url") ?: return ToolResult.Failure("Missing required input 'url'")
        val sha = required(input, "sha256") ?: return ToolResult.Failure("Missing required input 'sha256'")
        return client.catalogUpdate(url, sha).toToolResult()
    }
}

class FlashListDevicesTool(r: FlashNodeRegistry) : FlashNodeTool(r) {
    override val spec = spec("flash_list_devices", "Lists removable USB disks eligible for flashing on a node (enumeration only). Input: node.")
    override fun run(client: FlashClient, input: Map<String, String>) = client.listDevices().toToolResult { v ->
        val d = (v as FlashDeviceList).devices
        if (d.isEmpty()) "No eligible removable disks." else d.joinToString("\n") { "${it.name}  ${it.path}  ${it.size} bytes  ${it.model}  ${it.transport}" }
    }
}

class FlashVerifyImageTool(r: FlashNodeRegistry) : FlashNodeTool(r) {
    override val spec = spec("flash_verify_image", "Hashes an image file on the node and compares SHA-256. Inputs: node, image (absolute path), sha256.")
    override fun run(client: FlashClient, input: Map<String, String>): ToolResult {
        val image = required(input, "image") ?: return ToolResult.Failure("Missing required input 'image'")
        val sha = required(input, "sha256") ?: return ToolResult.Failure("Missing required input 'sha256'")
        return client.verifyImage(image, sha).toToolResult()
    }
}

class FlashReadbackVerifyTool(r: FlashNodeRegistry) : FlashNodeTool(r) {
    override val spec = spec(
        "flash_readback_verify",
        "Reads back the first <length> bytes of a removable disk on the node (via its pkexec helper) and checks SHA-256. " +
            "Inputs: node, device (/dev/sdX), sha256, length (bytes).",
    )
    override fun run(client: FlashClient, input: Map<String, String>): ToolResult {
        val dev = required(input, "device") ?: return ToolResult.Failure("Missing required input 'device'")
        val sha = required(input, "sha256") ?: return ToolResult.Failure("Missing required input 'sha256'")
        val len = required(input, "length")?.toLongOrNull()?.takeIf { it > 0 } ?: return ToolResult.Failure("'length' must be a positive integer")
        return client.readbackVerify(dev, sha, len).toToolResult()
    }
}

/**
 * DESTRUCTIVE: erases a removable disk on a remote machine. Beyond the per-call approval prompt,
 * the call must carry `confirm` equal to the device name — the same typed confirmation the GUI
 * demands — and the node re-checks that, the image hash, and device eligibility before its pkexec
 * helper writes and reads back. Progress frames are collected into the result summary.
 */
class FlashWriteImageTool(r: FlashNodeRegistry) : FlashNodeTool(r) {
    override val spec = spec(
        "flash_write_image",
        "ERASES a removable disk on a paired flash node and writes an image to it, then verifies by read-back. " +
            "Requires per-call human approval. Inputs: node, image (absolute path on node), device (/dev/sdX), sha256, " +
            "confirm (the device name typed by the human, e.g. sdb), catalog_id (optional).",
    )

    override fun run(client: FlashClient, input: Map<String, String>): ToolResult {
        val image = required(input, "image") ?: return ToolResult.Failure("Missing required input 'image'")
        val device = required(input, "device") ?: return ToolResult.Failure("Missing required input 'device'")
        val sha = required(input, "sha256") ?: return ToolResult.Failure("Missing required input 'sha256'")
        val confirm = required(input, "confirm")
            ?: return ToolResult.Failure("Refused: 'confirm' must be the device name typed by the human (e.g. ${device.removePrefix("/dev/")})")
        if (confirm != device && "/dev/$confirm" != device) return ToolResult.Failure("Refused: confirmation '$confirm' does not match device '$device'")
        var last: FlashProgress? = null
        var frames = 0
        val r = client.writeImage(WriteRequest(image, device, sha, confirm, required(input, "catalog_id"))) {
            last = it
            frames++
        }
        return r.toToolResult { "Wrote and verified $device ($frames progress frames; last ${last?.phase} ${last?.done}/${last?.total}). Result: $it" }
    }
}

fun flashTools(registry: FlashNodeRegistry): List<Tool> = listOf(
    FlashCapabilitiesTool(registry), FlashCatalogListTool(registry), FlashCatalogUpdateTool(registry), FlashListDevicesTool(registry),
    FlashVerifyImageTool(registry), FlashReadbackVerifyTool(registry), FlashWriteImageTool(registry),
)
