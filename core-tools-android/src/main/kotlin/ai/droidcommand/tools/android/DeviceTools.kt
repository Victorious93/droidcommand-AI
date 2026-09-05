package ai.droidcommand.tools.android

import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolSpec

private fun Map<String, String>.intOrFail(key: String): Result<Int> {
    val raw = this[key] ?: return Result.failure(IllegalArgumentException("Missing required input '$key'"))
    val value = raw.toIntOrNull() ?: return Result.failure(IllegalArgumentException("Input '$key' must be an integer, got '$raw'"))
    return Result.success(value)
}

private fun toToolResult(action: DeviceActionResult): ToolResult = when (action) {
    is DeviceActionResult.Success -> ToolResult.Success(action.message)
    is DeviceActionResult.Failure -> ToolResult.Failure(action.reason, action.cause)
}

class TapTool(private val device: DeviceController) : Tool {
    override val spec = ToolSpec(name = "tap", description = "Taps the screen at the given coordinates", securityLevel = SecurityLevel.SENSITIVE)

    override fun execute(input: Map<String, String>): ToolResult {
        val x = input.intOrFail("x").getOrElse { return ToolResult.Failure(it.message ?: "invalid input") }
        val y = input.intOrFail("y").getOrElse { return ToolResult.Failure(it.message ?: "invalid input") }
        return toToolResult(device.tap(x, y))
    }
}

class SwipeTool(private val device: DeviceController) : Tool {
    override val spec = ToolSpec(name = "swipe", description = "Swipes from one point to another", securityLevel = SecurityLevel.SENSITIVE)

    override fun execute(input: Map<String, String>): ToolResult {
        val startX = input.intOrFail("startX").getOrElse { return ToolResult.Failure(it.message ?: "invalid input") }
        val startY = input.intOrFail("startY").getOrElse { return ToolResult.Failure(it.message ?: "invalid input") }
        val endX = input.intOrFail("endX").getOrElse { return ToolResult.Failure(it.message ?: "invalid input") }
        val endY = input.intOrFail("endY").getOrElse { return ToolResult.Failure(it.message ?: "invalid input") }
        val durationMillis = input["durationMillis"]?.toLongOrNull() ?: 300L
        return toToolResult(device.swipe(startX, startY, endX, endY, durationMillis))
    }
}

class TypeTextTool(private val device: DeviceController) : Tool {
    override val spec = ToolSpec(name = "type_text", description = "Types text into the currently focused field", securityLevel = SecurityLevel.SENSITIVE)

    override fun execute(input: Map<String, String>): ToolResult {
        val text = input["text"] ?: return ToolResult.Failure("Missing required input 'text'")
        return toToolResult(device.typeText(text))
    }
}

class PressKeyTool(private val device: DeviceController) : Tool {
    override val spec = ToolSpec(name = "press_key", description = "Presses a device key (BACK, HOME, ENTER, ...)", securityLevel = SecurityLevel.SENSITIVE)

    override fun execute(input: Map<String, String>): ToolResult {
        val raw = input["key"] ?: return ToolResult.Failure("Missing required input 'key'")
        val key = runCatching { DeviceKey.valueOf(raw.uppercase()) }
            .getOrElse { return ToolResult.Failure("Unknown key '$raw'; expected one of ${DeviceKey.entries.joinToString()}") }
        return toToolResult(device.pressKey(key))
    }
}

class LaunchAppTool(private val device: DeviceController) : Tool {
    override val spec = ToolSpec(name = "launch_app", description = "Launches an installed app by package name", securityLevel = SecurityLevel.SENSITIVE)

    override fun execute(input: Map<String, String>): ToolResult {
        val packageName = input["packageName"] ?: return ToolResult.Failure("Missing required input 'packageName'")
        return toToolResult(device.launchApp(packageName))
    }
}

class ListInstalledAppsTool(private val device: DeviceController) : Tool {
    override val spec = ToolSpec(name = "list_installed_apps", description = "Lists installed apps")

    override fun execute(input: Map<String, String>): ToolResult = when (val result = device.listInstalledApps()) {
        is InstalledAppsResult.Success -> ToolResult.Success(
            result.apps.joinToString("\n") { "${it.packageName} (${it.appName}${it.versionName?.let { v -> ", $v" } ?: ""})" },
        )
        is InstalledAppsResult.Failure -> ToolResult.Failure(result.reason)
    }
}

class GetUiTreeTool(private val device: DeviceController) : Tool {
    override val spec = ToolSpec(name = "get_ui_tree", description = "Reads the current screen's accessibility/UI tree")

    override fun execute(input: Map<String, String>): ToolResult = when (val result = device.getUiTree()) {
        is UiTreeResult.Success -> ToolResult.Success(renderUiTree(result.tree))
        is UiTreeResult.Failure -> ToolResult.Failure(result.reason)
    }
}

class TakeScreenshotTool(private val device: DeviceController) : Tool {
    override val spec = ToolSpec(name = "take_screenshot", description = "Captures a screenshot of the current screen")

    override fun execute(input: Map<String, String>): ToolResult = when (val result = device.takeScreenshot()) {
        is ScreenshotResult.Success -> ToolResult.Success("Screenshot saved to ${result.path} (${result.widthPx}x${result.heightPx}, ${result.sizeBytes} bytes)")
        is ScreenshotResult.Failure -> ToolResult.Failure(result.reason)
    }
}
