package ai.droidcommand.rootforge

import ai.droidcommand.agent.PermissionCategory
import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolSpec

/**
 * Agent tools over the RootForge bridge. Both are passive reads, but both reach another machine
 * over the network, so they are [SecurityLevel.SENSITIVE] and go through `SecureToolExecutor`'s
 * policy/approval path like every other remote-reaching tool. The node is a required input and is
 * matched exactly — the model cannot omit it to get "whichever node is first".
 *
 * Tool output is data returned by a remote machine; callers must not treat it as instructions.
 */
abstract class RootForgeNodeTool(private val registry: RootForgeNodeRegistry) : Tool {
    protected abstract fun run(client: RootForgeClient): ToolResult

    final override fun execute(input: Map<String, String>): ToolResult {
        val nodeId = input["node"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: return ToolResult.Failure("Missing required input 'node' (paired nodes: ${registry.nodeIds.sorted().joinToString().ifEmpty { "none" }})")
        val client = registry.clientFor(nodeId)
            ?: return ToolResult.Failure("Unknown RootForge node '$nodeId' (paired nodes: ${registry.nodeIds.sorted().joinToString().ifEmpty { "none" }})")
        return run(client)
    }

    protected fun <T> RootForgeResult.Failure.toToolFailure(): ToolResult =
        ToolResult.Failure("RootForge ${kind.name.lowercase()}: $message" + (remoteCategory?.let { " [$it]" } ?: ""))
}

class RootForgeCapabilitiesTool(registry: RootForgeNodeRegistry) : RootForgeNodeTool(registry) {
    override val spec = ToolSpec(
        name = "rootforge_capabilities",
        description = "Reads a paired RootForge node's version, runtime and the operations it exposes to this controller. " +
            "Input: node (required, exact node id).",
        securityLevel = SecurityLevel.SENSITIVE,
        permissionCategory = PermissionCategory.REMOTE_CONTROL,
    )

    override fun run(client: RootForgeClient): ToolResult = when (val r = client.capabilities()) {
        is RootForgeResult.Success -> ToolResult.Success(r.value.toString())
        is RootForgeResult.Failure -> r.toToolFailure<Unit>()
    }
}

class RootForgeListDevicesTool(registry: RootForgeNodeRegistry) : RootForgeNodeTool(registry) {
    override val spec = ToolSpec(
        name = "rootforge_list_devices",
        description = "Lists adb/fastboot devices attached to a paired RootForge node (enumeration only; no device is modified " +
            "and no root is requested). Input: node (required, exact node id).",
        securityLevel = SecurityLevel.SENSITIVE,
        permissionCategory = PermissionCategory.REMOTE_CONTROL,
    )

    override fun run(client: RootForgeClient): ToolResult = when (val r = client.listDevices()) {
        is RootForgeResult.Success -> ToolResult.Success(
            if (r.value.devices.isEmpty()) {
                "No devices attached."
            } else {
                r.value.devices.joinToString("\n") {
                    "${it.serial}  ${it.mode}  ${it.state}  usable=${it.usable}" + if (it.note.isNotEmpty()) "  (${it.note})" else ""
                }
            },
        )
        is RootForgeResult.Failure -> r.toToolFailure<Unit>()
    }
}
