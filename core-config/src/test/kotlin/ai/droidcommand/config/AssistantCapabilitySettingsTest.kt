package ai.droidcommand.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AssistantCapabilitySettingsTest {
    @Test
    fun `defaults are all off`() {
        val settings = AssistantCapabilitySettings()
        assertEquals(AssistantCapabilitySettings.DISABLED, settings)
        assertTrue(settings.enabledCapabilities().isEmpty())
        for (capability in AssistantCapability.entries) {
            assertFalse(settings.isEnabled(capability))
            assertTrue(settings.disabledReason(capability).contains("disabled"))
        }
    }

    @Test
    fun `encoded settings never mention external control or secrets`() {
        val encoded = FileAssistantCapabilitySettingsStore.encode(AssistantCapabilitySettings.DISABLED)
        assertFalse(encoded.contains("externalControlEnabled"))
        assertFalse(encoded.contains("destructive"))
        assertFalse(encoded.contains("confirmation"))
        assertFalse(encoded.contains("password"))
        assertFalse(encoded.contains("api_key"))
        assertFalse(encoded.contains("secret"))
    }

    @Test
    fun `absent config keys stay off`() {
        val loaded = AssistantCapabilitySettings.load(MapConfigSource(emptyMap()))
        assertEquals(AssistantCapabilitySettings.DISABLED, loaded)
    }

    @Test
    fun `only the literal true enables a capability`() {
        val loaded = AssistantCapabilitySettings.load(
            MapConfigSource(
                mapOf(
                    ConfigKeys.MCP_CLIENT_ENABLED to "true",
                    ConfigKeys.LINUX_ENVIRONMENT_ENABLED to "TRUE",
                    ConfigKeys.BACKGROUND_AUTOMATION_ENABLED to "false",
                    ConfigKeys.MAILBOX_ACCESS_ENABLED to "yes",
                    ConfigKeys.SMS_ACCESS_ENABLED to "",
                    ConfigKeys.CLOUD_TRANSFER_ENABLED to "1",
                ),
            ),
        )
        assertTrue(loaded.mcpClientEnabled)
        assertTrue(loaded.linuxEnvironmentEnabled)
        assertFalse(loaded.backgroundAutomationEnabled)
        assertFalse(loaded.mailboxAccessEnabled)
        assertFalse(loaded.smsAccessEnabled)
        assertFalse(loaded.cloudTransferEnabled)
        assertEquals(
            listOf(AssistantCapability.MCP_CLIENT, AssistantCapability.LINUX_ENVIRONMENT),
            loaded.enabledCapabilities(),
        )
    }

    @Test
    fun `an imported externalControlEnabled key is ignored and does not enable anything`() {
        val text = """
            mcpClientEnabled=false
            externalControlEnabled=true
            destructive=true
            confirmation_required=false
        """.trimIndent()
        val loaded = FileAssistantCapabilitySettingsStore.decode(text)
        assertEquals(AssistantCapabilitySettings.DISABLED, loaded)
        assertFalse(loaded.mcpClientEnabled)
        assertFalse(loaded.cloudTransferEnabled)
    }
}
