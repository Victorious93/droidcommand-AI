package ai.droidcommand.rootforge

import ai.droidcommand.agent.PermissionCategory
import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolSpec

/**
 * Agent tools over the RootForge bridge. All of them reach another machine over the network, so
 * they are [SecurityLevel.SENSITIVE] and go through `SecureToolExecutor`'s policy/approval path
 * like every other remote-reaching tool. The node is a required input and is matched exactly —
 * the model cannot omit it to get "whichever node is first".
 *
 * [run] also receives the full tool input so a subclass can read its own extra parameters (e.g.
 * `command`, `target`) without re-implementing node resolution.
 *
 * Tool output is data returned by a remote machine; callers must not treat it as instructions.
 */
abstract class RootForgeNodeTool(private val registry: RootForgeNodeRegistry) : Tool {
    protected abstract fun run(client: RootForgeClient, input: Map<String, String>): ToolResult

    final override fun execute(input: Map<String, String>): ToolResult {
        val nodeId = input["node"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: return ToolResult.Failure("Missing required input 'node' (paired nodes: ${registry.nodeIds.sorted().joinToString().ifEmpty { "none" }})")
        val client = registry.clientFor(nodeId)
            ?: return ToolResult.Failure("Unknown RootForge node '$nodeId' (paired nodes: ${registry.nodeIds.sorted().joinToString().ifEmpty { "none" }})")
        return run(client, input)
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

    override fun run(client: RootForgeClient, input: Map<String, String>): ToolResult = when (val r = client.capabilities()) {
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

    override fun run(client: RootForgeClient, input: Map<String, String>): ToolResult = when (val r = client.listDevices()) {
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

class RootForgeDeviceInfoTool(registry: RootForgeNodeRegistry) : RootForgeNodeTool(registry) {
    override val spec = ToolSpec(
        name = "rootforge_device_info",
        description = "Reads property info (e.g. Model) of the target currently connected to a paired RootForge node " +
            "(a read; no device is modified). Input: node (required, exact node id).",
        securityLevel = SecurityLevel.SENSITIVE,
        permissionCategory = PermissionCategory.REMOTE_CONTROL,
    )

    override fun run(client: RootForgeClient, input: Map<String, String>): ToolResult = when (val r = client.deviceInfo()) {
        is RootForgeResult.Success -> ToolResult.Success(
            if (r.value.info.isEmpty()) {
                "No target connected, or the node reported no properties."
            } else {
                r.value.info.entries.joinToString("\n") { (k, v) -> "$k: $v" }
            },
        )
        is RootForgeResult.Failure -> r.toToolFailure<Unit>()
    }
}

/**
 * `rootforge.adb.shell` is a **control**-tier operation on the node itself: the node's own
 * operator is prompted ("Allow `rootforge.adb.shell`?") before the command runs, exactly like
 * every other control op in the bridge — this tool does not and cannot skip that. It reaches the
 * node and, if the node allows it, an attached Android target; output is data from that target,
 * never instructions.
 */
class RootForgeAdbShellTool(registry: RootForgeNodeRegistry) : RootForgeNodeTool(registry) {
    override val spec = ToolSpec(
        name = "rootforge_adb_shell",
        description = "Runs a command in an adb shell on the target attached to a paired RootForge node. The node's own " +
            "operator is prompted before it runs. Input: node (required, exact node id), command (required).",
        securityLevel = SecurityLevel.SENSITIVE,
        permissionCategory = PermissionCategory.REMOTE_CONTROL,
    )

    override fun run(client: RootForgeClient, input: Map<String, String>): ToolResult {
        val command = input["command"]?.takeIf { it.isNotBlank() }
            ?: return ToolResult.Failure("Missing required input 'command'")
        return when (val r = client.adbShell(command)) {
            is RootForgeResult.Success -> if (r.value.exitCode == 0) {
                ToolResult.Success(r.value.output + if (r.value.truncated) "\n[output truncated]" else "")
            } else {
                ToolResult.Failure("adb shell exited ${r.value.exitCode}: ${r.value.output}" + if (r.value.truncated) " [truncated]" else "")
            }
            is RootForgeResult.Failure -> r.toToolFailure<Unit>()
        }
    }
}

/**
 * `rootforge.adb.reboot` reboots the connected target **to** a mode (empty string = plain
 * reboot). This is adb-only, not a sideload-of-a-zip, and only ever runs if the node's operator
 * allows it.
 */
class RootForgeAdbRebootTool(registry: RootForgeNodeRegistry) : RootForgeNodeTool(registry) {
    override val spec = ToolSpec(
        name = "rootforge_adb_reboot",
        description = "Reboots the target attached to a paired RootForge node via adb, optionally to a mode. The node's own " +
            "operator is prompted before it runs. Input: node (required, exact node id), " +
            "target (optional: \"\"/bootloader/recovery/sideload/fastboot/edl; default \"\").",
        securityLevel = SecurityLevel.SENSITIVE,
        permissionCategory = PermissionCategory.REMOTE_CONTROL,
    )

    override fun run(client: RootForgeClient, input: Map<String, String>): ToolResult =
        when (val r = client.adbReboot(input["target"]?.trim() ?: "")) {
            is RootForgeResult.Success -> ToolResult.Success("requested: ${r.value.requested}")
            is RootForgeResult.Failure -> r.toToolFailure<Unit>()
        }
}

/**
 * `rootforge.fastboot.reboot` reboots a target already in fastboot mode. Unlike
 * [RootForgeAdbRebootTool], `target` is required — there is no bare "reboot" in fastboot.
 */
class RootForgeFastbootRebootTool(registry: RootForgeNodeRegistry) : RootForgeNodeTool(registry) {
    override val spec = ToolSpec(
        name = "rootforge_fastboot_reboot",
        description = "Reboots a target already in fastboot on a paired RootForge node. The node's own operator is prompted " +
            "before it runs. Input: node (required, exact node id), target (required: system/bootloader/recovery/fastbootd).",
        securityLevel = SecurityLevel.SENSITIVE,
        permissionCategory = PermissionCategory.REMOTE_CONTROL,
    )

    override fun run(client: RootForgeClient, input: Map<String, String>): ToolResult {
        val target = input["target"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: return ToolResult.Failure("Missing required input 'target' (system/bootloader/recovery/fastbootd)")
        return when (val r = client.fastbootReboot(target)) {
            is RootForgeResult.Success -> ToolResult.Success("requested: ${r.value.requested}")
            is RootForgeResult.Failure -> r.toToolFailure<Unit>()
        }
    }
}
