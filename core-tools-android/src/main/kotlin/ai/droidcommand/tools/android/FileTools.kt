package ai.droidcommand.tools.android

import ai.droidcommand.agent.PermissionCategory
import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolSpec

/**
 * File operations (ROADMAP-038 / master prompt Phase 5 "Files": read, write,
 * move, copy, delete, directory inspection). All destructive/mutating
 * operations are [SecurityLevel.SENSITIVE], matching the other device tools
 * in this package — none of them are more dangerous than a real device
 * action, but they are never [SecurityLevel.NORMAL] either, per the master
 * prompt's "delete authorized files" phrasing.
 */
class ReadFileTool(private val device: DeviceController) : Tool {
    override val spec = ToolSpec(
        name = "read_file",
        description = "Reads the contents of a file on the device",
        securityLevel = SecurityLevel.SENSITIVE,
        permissionCategory = PermissionCategory.FILES,
    )

    override fun execute(input: Map<String, String>): ToolResult {
        val path = input["path"] ?: return ToolResult.Failure("Missing required input 'path'")
        return when (val result = device.readFile(path)) {
            is FileReadResult.Success -> ToolResult.Success(result.content)
            is FileReadResult.Failure -> ToolResult.Failure(result.reason)
        }
    }
}

class WriteFileTool(private val device: DeviceController) : Tool {
    override val spec = ToolSpec(
        name = "write_file",
        description = "Writes text content to a file on the device, optionally appending",
        securityLevel = SecurityLevel.SENSITIVE,
        permissionCategory = PermissionCategory.FILES,
    )

    override fun execute(input: Map<String, String>): ToolResult {
        val path = input["path"] ?: return ToolResult.Failure("Missing required input 'path'")
        val content = input["content"] ?: return ToolResult.Failure("Missing required input 'content'")
        val append = input["append"]?.toBooleanStrictOrNull() ?: false
        return toToolResult(device.writeFile(path, content, append))
    }
}

class MoveFileTool(private val device: DeviceController) : Tool {
    override val spec = ToolSpec(
        name = "move_file",
        description = "Moves or renames a file on the device",
        securityLevel = SecurityLevel.SENSITIVE,
        permissionCategory = PermissionCategory.FILES,
    )

    override fun execute(input: Map<String, String>): ToolResult {
        val fromPath = input["fromPath"] ?: return ToolResult.Failure("Missing required input 'fromPath'")
        val toPath = input["toPath"] ?: return ToolResult.Failure("Missing required input 'toPath'")
        return toToolResult(device.moveFile(fromPath, toPath))
    }
}

class CopyFileTool(private val device: DeviceController) : Tool {
    override val spec = ToolSpec(
        name = "copy_file",
        description = "Copies a file on the device",
        securityLevel = SecurityLevel.SENSITIVE,
        permissionCategory = PermissionCategory.FILES,
    )

    override fun execute(input: Map<String, String>): ToolResult {
        val fromPath = input["fromPath"] ?: return ToolResult.Failure("Missing required input 'fromPath'")
        val toPath = input["toPath"] ?: return ToolResult.Failure("Missing required input 'toPath'")
        return toToolResult(device.copyFile(fromPath, toPath))
    }
}

class DeleteFileTool(private val device: DeviceController) : Tool {
    override val spec = ToolSpec(
        name = "delete_file",
        description = "Deletes an authorized file on the device",
        securityLevel = SecurityLevel.SENSITIVE,
        permissionCategory = PermissionCategory.FILES,
    )

    override fun execute(input: Map<String, String>): ToolResult {
        val path = input["path"] ?: return ToolResult.Failure("Missing required input 'path'")
        return toToolResult(device.deleteFile(path))
    }
}

class ListDirectoryTool(private val device: DeviceController) : Tool {
    override val spec = ToolSpec(name = "list_directory", description = "Lists the contents of a directory on the device", permissionCategory = PermissionCategory.FILES)

    override fun execute(input: Map<String, String>): ToolResult {
        val path = input["path"] ?: return ToolResult.Failure("Missing required input 'path'")
        return when (val result = device.listDirectory(path)) {
            is FileListResult.Success -> ToolResult.Success(
                result.entries.joinToString("\n") { entry ->
                    if (entry.isDirectory) "${entry.name}/ (dir)" else "${entry.name} (${entry.sizeBytes} bytes)"
                },
            )
            is FileListResult.Failure -> ToolResult.Failure(result.reason)
        }
    }
}
