package ai.droidcommand.tools.android

import ai.droidcommand.agent.PermissionCategory
import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolSpec

/**
 * Communications (ROADMAP-039 / master prompt Phase 5 "Communications" —
 * "Where Android permissions and environment allow: SMS, calls, contacts").
 * All three are [SecurityLevel.SENSITIVE]: sending an SMS or placing a call
 * has a real-world side effect outside the device itself (cost, an actual
 * person contacted) that no other tool in this package carries, and
 * contacts are as sensitive as anything [ReadFileTool]/[GetClipboardTool]
 * already treat with caution — real names and phone numbers, not metadata.
 */
class SendSmsTool(private val device: DeviceController) : Tool {
    override val spec = ToolSpec(
        name = "send_sms",
        description = "Sends an SMS message to a phone number",
        securityLevel = SecurityLevel.SENSITIVE,
        permissionCategory = PermissionCategory.DEVICE_CONTROL,
    )

    override fun execute(input: Map<String, String>): ToolResult {
        val phoneNumber = input["phoneNumber"] ?: return ToolResult.Failure("Missing required input 'phoneNumber'")
        val message = input["message"] ?: return ToolResult.Failure("Missing required input 'message'")
        return toToolResult(device.sendSms(phoneNumber, message))
    }
}

class MakeCallTool(private val device: DeviceController) : Tool {
    override val spec = ToolSpec(
        name = "make_call",
        description = "Places a phone call to a phone number",
        securityLevel = SecurityLevel.SENSITIVE,
        permissionCategory = PermissionCategory.DEVICE_CONTROL,
    )

    override fun execute(input: Map<String, String>): ToolResult {
        val phoneNumber = input["phoneNumber"] ?: return ToolResult.Failure("Missing required input 'phoneNumber'")
        return toToolResult(device.makeCall(phoneNumber))
    }
}

class ListContactsTool(private val device: DeviceController) : Tool {
    override val spec = ToolSpec(
        name = "list_contacts",
        description = "Lists the device's contacts",
        securityLevel = SecurityLevel.SENSITIVE,
        permissionCategory = PermissionCategory.VIEW,
    )

    override fun execute(input: Map<String, String>): ToolResult = when (val result = device.listContacts()) {
        is ContactsResult.Success -> ToolResult.Success(
            result.contacts.joinToString("\n") { "${it.name}: ${it.phoneNumbers.joinToString(", ")}" },
        )
        is ContactsResult.Failure -> ToolResult.Failure(result.reason)
    }
}
