package ai.droidcommand.remotecontrol

import ai.droidcommand.security.FleetIntegrationClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemoteCommandMappingTest {
    @Test
    fun `wipe power-off and lock are destructive and discrete`() {
        DestructiveRemoteActions.DEFINED.forEach { action ->
            assertTrue(action.destructiveByDefinition, action.name)
            assertEquals(DeliveryMode.DISCRETE_COMMAND, action.allowedDelivery, action.name)
            val mapping = mapping(action = action, deliveryMode = DeliveryMode.DISCRETE_COMMAND, destructive = true, smsCommandId = action.name.lowercase())
            assertTrue(mapping.effectiveDestructive)
        }
        assertEquals(setOf(RemoteAction.WIPE, RemoteAction.POWER_OFF, RemoteAction.LOCK), DestructiveRemoteActions.DEFINED)
    }

    @Test
    fun `rustdesk is full-screen and not destructive`() {
        val mapping = mapping(
            action = RemoteAction.RUSTDESK_SCREEN,
            deliveryMode = DeliveryMode.FULL_SCREEN_CONTROL,
            destructive = false,
            voicePhrase = "take the screen",
            smsCommandId = null,
        )
        assertFalse(mapping.effectiveDestructive)
        assertEquals(RemoteControlToolNames.SCREEN, mapping.toolName())
    }

    @Test
    fun `custom flagged destructive uses the never-yolo tool name`() {
        val mapping = mapping(action = RemoteAction.CUSTOM, destructive = true, smsCommandId = "ping-hard")
        assertTrue(mapping.effectiveDestructive)
        assertEquals(RemoteControlToolNames.DESTRUCTIVE, mapping.toolName())
    }

    @Test
    fun `refuses a wipe stored as non-destructive`() {
        assertFailsWith<IllegalArgumentException> {
            mapping(action = RemoteAction.WIPE, destructive = false, smsCommandId = "wipe")
        }
    }

    @Test
    fun `refuses rustdesk marked discrete`() {
        assertFailsWith<IllegalArgumentException> {
            mapping(action = RemoteAction.RUSTDESK_SCREEN, deliveryMode = DeliveryMode.DISCRETE_COMMAND, smsCommandId = "screen")
        }
    }

    @Test
    fun `requires a voice phrase or sms command id`() {
        assertFailsWith<IllegalArgumentException> {
            mapping(voicePhrase = null, smsCommandId = null)
        }
    }

    @Test
    fun `tool names for discrete non-destructive stay remote_command`() {
        val mapping = mapping(action = RemoteAction.CUSTOM, destructive = false, smsCommandId = "ping")
        assertEquals(RemoteControlToolNames.COMMAND, mapping.toolName())
        assertEquals(RemoteControlToolNames.WIPE, mapping(action = RemoteAction.WIPE, destructive = true, smsCommandId = "wipe").toolName())
        assertEquals(RemoteControlToolNames.POWER_OFF, mapping(action = RemoteAction.POWER_OFF, destructive = true, smsCommandId = "off").toolName())
        assertEquals(RemoteControlToolNames.LOCK, mapping(action = RemoteAction.LOCK, destructive = true, smsCommandId = "lock").toolName())
    }
}

internal fun mapping(
    id: String = "map-1",
    displayName: String = "Test",
    voicePhrase: String? = "open sesame",
    smsCommandId: String? = "cmd-1",
    targetNodeId: String = "node-1",
    action: RemoteAction = RemoteAction.CUSTOM,
    deliveryMode: DeliveryMode = action.allowedDelivery,
    destructive: Boolean = action.destructiveByDefinition,
    extras: Map<String, String> = emptyMap(),
) = RemoteCommandMapping(
    id = id,
    displayName = displayName,
    voicePhrase = voicePhrase,
    smsCommandId = smsCommandId,
    targetNodeId = targetNodeId,
    action = action,
    deliveryMode = deliveryMode,
    destructive = destructive,
    extras = extras,
)

internal fun sampleMacro(
    id: String = "macro-1",
    displayName: String = "Sample macro",
    voicePhrase: String? = null,
    smsCommandId: String? = null,
    destructive: Boolean = false,
): Macro {
    val trigger = when {
        !smsCommandId.isNullOrBlank() ->
            MacroTrigger(kind = MacroTriggerKind.SMS_COMMAND_ID, smsCommandId = smsCommandId)
        !voicePhrase.isNullOrBlank() ->
            MacroTrigger(kind = MacroTriggerKind.VOICE, voicePhrase = voicePhrase)
        else -> MacroTrigger(kind = MacroTriggerKind.MANUAL)
    }
    return Macro(
        id = id,
        displayName = displayName,
        steps = listOf(
            MacroStep(
                id = "step-1",
                target = NodeRef(
                    nodeId = "node-1",
                    integrationClass = FleetIntegrationClass.EXTERNAL_NODE,
                    packageName = RemoteCommandEnvelope.VICTOR_SMS_PACKAGE,
                ),
                actionRef = "custom",
                args = emptyMap(),
                destructive = destructive,
            ),
        ),
        trigger = trigger,
    )
}
