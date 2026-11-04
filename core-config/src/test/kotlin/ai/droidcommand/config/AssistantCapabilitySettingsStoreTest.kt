package ai.droidcommand.config

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AssistantCapabilitySettingsStoreTest {
    @Test
    fun `in-memory store starts disabled and round-trips an enable`() {
        val store = InMemoryAssistantCapabilitySettingsStore()
        assertEquals(AssistantCapabilitySettings.DISABLED, store.load())
        val enabled = AssistantCapabilitySettings(mcpClientEnabled = true)
        store.save(enabled)
        assertEquals(enabled, store.load())
        assertTrue(store.load().mcpClientEnabled)
        store.save(AssistantCapabilitySettings.DISABLED)
        assertFalse(store.load().mcpClientEnabled)
    }

    @Test
    fun `file store missing file is disabled`() {
        val dir = Files.createTempDirectory("kai-settings")
        val store = FileAssistantCapabilitySettingsStore(dir.resolve("missing.settings"))
        assertEquals(AssistantCapabilitySettings.DISABLED, store.load())
    }

    @Test
    fun `file store survives a fresh instance over the same path`() {
        val file = Files.createTempDirectory("kai-settings").resolve("assistant.settings")
        val first = FileAssistantCapabilitySettingsStore(file)
        first.save(AssistantCapabilitySettings(generatedUiEnabled = true, kaiBuildEnabled = true))
        val second = FileAssistantCapabilitySettingsStore(file)
        val loaded = second.load()
        assertTrue(loaded.generatedUiEnabled)
        assertTrue(loaded.kaiBuildEnabled)
        assertFalse(loaded.mcpClientEnabled)
        assertFalse(loaded.linuxEnvironmentEnabled)
        assertFalse(loaded.mailboxAccessEnabled)
        assertFalse(loaded.brainEnabled)
        val encoded = Files.readString(file)
        assertFalse(encoded.contains("externalControlEnabled"))
        assertFalse(encoded.contains("password"))
        assertFalse(encoded.contains("api_key"))
        assertFalse(encoded.contains("secret"))
    }

    @Test
    fun `corrupt or unrecognized file content fails closed to all-off`() {
        val file = Files.createTempDirectory("kai-settings").resolve("assistant.settings")
        Files.writeString(file, "this is not a settings file \u0000 {\"mcpClientEnabled\":true}")
        val store = FileAssistantCapabilitySettingsStore(file)
        assertEquals(AssistantCapabilitySettings.DISABLED, store.load())
    }

    @Test
    fun `turning a capability off stops reporting it enabled`() {
        val store = InMemoryAssistantCapabilitySettingsStore(
            AssistantCapabilitySettings(backgroundAutomationEnabled = true, smsAccessEnabled = true),
        )
        assertEquals(
            listOf(AssistantCapability.BACKGROUND_AUTOMATION, AssistantCapability.SMS_ACCESS),
            store.load().enabledCapabilities(),
        )
        store.save(store.load().copy(backgroundAutomationEnabled = false))
        assertEquals(listOf(AssistantCapability.SMS_ACCESS), store.load().enabledCapabilities())
    }

    @Test
    fun `brain stays off unless the owner enables it and that does not enable other gates`() {
        val file = Files.createTempDirectory("kai-settings").resolve("assistant.settings")
        val store = FileAssistantCapabilitySettingsStore(file)
        store.save(AssistantCapabilitySettings(brainEnabled = true))
        val loaded = FileAssistantCapabilitySettingsStore(file).load()
        assertTrue(loaded.brainEnabled)
        assertEquals(listOf(AssistantCapability.BRAIN), loaded.enabledCapabilities())
        assertFalse(loaded.mcpClientEnabled)
        assertFalse(loaded.mailboxAccessEnabled)
        assertFalse(Files.readString(file).contains("externalControlEnabled"))
    }
}
