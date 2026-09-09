package ai.droidcommand.tools.android

import ai.droidcommand.agent.ToolResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CommunicationsToolsTest {
    @Test
    fun `SendSmsTool delegates the phone number and message`() {
        val device = ScriptedDeviceController(sendSmsResult = DeviceActionResult.Success("sent"))
        val result = SendSmsTool(device).execute(mapOf("phoneNumber" to "555-0100", "message" to "hi"))
        assertIs<ToolResult.Success>(result)
        assertEquals(listOf("555-0100" to "hi"), device.sendSmsCalls)
    }

    @Test
    fun `SendSmsTool fails without calling the device when message is missing`() {
        val device = ScriptedDeviceController()
        val result = SendSmsTool(device).execute(mapOf("phoneNumber" to "555-0100"))
        assertIs<ToolResult.Failure>(result)
        assertEquals(0, device.sendSmsCalls.size)
    }

    @Test
    fun `SendSmsTool fails without calling the device when phoneNumber is missing`() {
        val device = ScriptedDeviceController()
        val result = SendSmsTool(device).execute(mapOf("message" to "hi"))
        assertIs<ToolResult.Failure>(result)
        assertEquals(0, device.sendSmsCalls.size)
    }

    @Test
    fun `MakeCallTool delegates the phone number`() {
        val device = ScriptedDeviceController(makeCallResult = DeviceActionResult.Success("calling"))
        val result = MakeCallTool(device).execute(mapOf("phoneNumber" to "555-0100"))
        assertIs<ToolResult.Success>(result)
        assertEquals(listOf("555-0100"), device.makeCallCalls)
    }

    @Test
    fun `MakeCallTool fails without calling the device when phoneNumber is missing`() {
        val device = ScriptedDeviceController()
        val result = MakeCallTool(device).execute(emptyMap())
        assertIs<ToolResult.Failure>(result)
        assertEquals(0, device.makeCallCalls.size)
    }

    @Test
    fun `ListContactsTool formats each contact on its own line`() {
        val device = ScriptedDeviceController(
            contactsResult = ContactsResult.Success(
                listOf(
                    ContactEntry("Alice", listOf("555-0100")),
                    ContactEntry("Bob", listOf("555-0200", "555-0201")),
                ),
            ),
        )
        val result = assertIs<ToolResult.Success>(ListContactsTool(device).execute(emptyMap()))
        assertEquals(2, result.output.lines().size)
        assertEquals(true, result.output.contains("Alice: 555-0100"))
        assertEquals(true, result.output.contains("Bob: 555-0200, 555-0201"))
    }

    @Test
    fun `ListContactsTool surfaces a device failure`() {
        val device = ScriptedDeviceController(contactsResult = ContactsResult.Failure("no device"))
        assertIs<ToolResult.Failure>(ListContactsTool(device).execute(emptyMap()))
    }
}
