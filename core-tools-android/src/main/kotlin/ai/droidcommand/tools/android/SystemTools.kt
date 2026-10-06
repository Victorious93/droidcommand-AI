package ai.droidcommand.tools.android

import ai.droidcommand.agent.PermissionCategory
import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolSpec

/**
 * System queries, clipboard, notifications, and broadcast intents (ROADMAP-037 / master
 * prompt Phase 5 "System"): battery, network state, storage, clipboard, notifications,
 * and send_broadcast — device info already existed via [DeviceController.getDeviceInfo].
 * Reads are [SecurityLevel.NORMAL] (metadata); clipboard and notification content are
 * [SecurityLevel.SENSITIVE] (can hold credentials); broadcasting an intent is
 * [SecurityLevel.SENSITIVE] (can trigger arbitrary side effects on the device).
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

class ListNotificationsTool(private val device: DeviceController) : Tool {
    override val spec = ToolSpec(
        name = "list_notifications",
        description = "Lists active notifications: package name, ID, and tag for each",
        securityLevel = SecurityLevel.SENSITIVE,
        permissionCategory = PermissionCategory.VIEW,
    )

    override fun execute(input: Map<String, String>): ToolResult = when (val result = device.listNotifications()) {
        is NotificationsResult.Success -> {
            if (result.notifications.isEmpty()) {
                ToolResult.Success("No active notifications")
            } else {
                ToolResult.Success(
                    "${result.notifications.size} active notification(s):\n" +
                        result.notifications.joinToString("\n") { n ->
                            "  ${n.packageName} id=${n.id}${n.tag?.let { " tag=$it" } ?: ""}"
                        },
                )
            }
        }
        is NotificationsResult.Failure -> ToolResult.Failure(result.reason)
    }
}

class SendBroadcastTool(private val device: DeviceController) : Tool {
    override val spec = ToolSpec(
        name = "send_broadcast",
        description = "Sends an Android broadcast intent; extras is optional 'key=value,key=value' pairs",
        securityLevel = SecurityLevel.SENSITIVE,
        permissionCategory = PermissionCategory.DEVICE_CONTROL,
    )

    override fun execute(input: Map<String, String>): ToolResult {
        val action = input["action"] ?: return ToolResult.Failure("Missing required input 'action'")
        val packageName = input["package_name"]
        val extrasRaw = input["extras"] ?: ""
        val extras = if (extrasRaw.isBlank()) {
            emptyMap()
        } else {
            extrasRaw.split(",").mapNotNull { entry ->
                val eq = entry.indexOf('=')
                if (eq < 1) null else entry.substring(0, eq).trim() to entry.substring(eq + 1).trim()
            }.toMap()
        }
        return toToolResult(device.sendBroadcast(action, packageName, extras))
    }
}
