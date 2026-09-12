package ai.droidcommand.tools.android

import ai.droidcommand.agent.PermissionCategory
import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolSpec

/**
 * System queries and the clipboard (ROADMAP-037 / master prompt Phase 5
 * "System": battery, network state, storage, clipboard — device info
 * already existed via [DeviceController.getDeviceInfo] before this file,
 * though no `Tool` wrapper for it exists yet either). Intents and
 * notifications, the other two items in the same master-prompt list, are
 * deliberately not covered here — their semantics (which intents are safe
 * to fire, how a notification listener would even be wired without an
 * Android app module) need more design than a mechanical extension of this
 * pattern, unlike the queries below. Reads are [SecurityLevel.NORMAL]
 * (metadata, no different in kind from [ListInstalledAppsTool]); clipboard
 * access is [SecurityLevel.SENSITIVE] in both directions, since a device's
 * clipboard can hold anything a user copied (credentials, tokens) and can
 * be used to inject content into whatever the user pastes into next.
 */
class GetBatteryStatusTool(private val device: DeviceController) : Tool {
    override val spec = ToolSpec(
        name = "get_battery_status",
        description = "Reads the device's battery level and charging state",
        permissionCategory = PermissionCategory.VIEW,
    )

    override fun execute(input: Map<String, String>): ToolResult = when (val result = device.getBatteryStatus()) {
        is BatteryStatusResult.Success -> ToolResult.Success(
            "${result.status.levelPercent}% (${if (result.status.isCharging) "charging" else "not charging"})",
        )
        is BatteryStatusResult.Failure -> ToolResult.Failure(result.reason)
    }
}

class GetNetworkStateTool(private val device: DeviceController) : Tool {
    override val spec = ToolSpec(
        name = "get_network_state",
        description = "Reads the device's current network connectivity",
        permissionCategory = PermissionCategory.NETWORK,
    )

    override fun execute(input: Map<String, String>): ToolResult = when (val result = device.getNetworkState()) {
        is NetworkStateResult.Success -> ToolResult.Success(
            "${result.state.type} (${if (result.state.isConnected) "connected" else "disconnected"})",
        )
        is NetworkStateResult.Failure -> ToolResult.Failure(result.reason)
    }
}

class GetStorageInfoTool(private val device: DeviceController) : Tool {
    override val spec = ToolSpec(
        name = "get_storage_info",
        description = "Reads the device's total and free storage",
        permissionCategory = PermissionCategory.VIEW,
    )

    override fun execute(input: Map<String, String>): ToolResult = when (val result = device.getStorageInfo()) {
        is StorageInfoResult.Success -> ToolResult.Success("${result.info.freeBytes} free of ${result.info.totalBytes} bytes")
        is StorageInfoResult.Failure -> ToolResult.Failure(result.reason)
    }
}

class GetClipboardTool(private val device: DeviceController) : Tool {
    override val spec = ToolSpec(
        name = "get_clipboard",
        description = "Reads the device's current clipboard text",
        securityLevel = SecurityLevel.SENSITIVE,
        permissionCategory = PermissionCategory.VIEW,
    )

    override fun execute(input: Map<String, String>): ToolResult = when (val result = device.getClipboardText()) {
        is ClipboardReadResult.Success -> ToolResult.Success(result.text)
        is ClipboardReadResult.Failure -> ToolResult.Failure(result.reason)
    }
}

class SetClipboardTool(private val device: DeviceController) : Tool {
    override val spec = ToolSpec(
        name = "set_clipboard",
        description = "Sets the device's clipboard text",
        securityLevel = SecurityLevel.SENSITIVE,
        permissionCategory = PermissionCategory.DEVICE_CONTROL,
    )

    override fun execute(input: Map<String, String>): ToolResult {
        val text = input["text"] ?: return ToolResult.Failure("Missing required input 'text'")
        return toToolResult(device.setClipboardText(text))
    }
}
