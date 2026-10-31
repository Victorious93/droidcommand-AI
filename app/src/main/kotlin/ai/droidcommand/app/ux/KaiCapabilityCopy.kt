package ai.droidcommand.app.ux

import ai.droidcommand.config.AssistantCapability
import ai.droidcommand.config.AssistantCapabilitySettings

/**
 * Owner-facing labels for the nine Kai-integration gates.
 * The gates themselves stay default OFF; this is display copy only.
 */
data class KaiCapabilityRow(
    val capability: AssistantCapability,
    val label: String,
    val detail: String,
    val enabled: Boolean,
)

object KaiCapabilityCopy {
    fun rows(settings: AssistantCapabilitySettings = AssistantCapabilitySettings.DISABLED): List<KaiCapabilityRow> =
        AssistantCapability.entries.map { capability ->
            KaiCapabilityRow(
                capability = capability,
                label = label(capability),
                detail = "Off until a later phase turns this on.",
                enabled = settings.isEnabled(capability),
            )
        }

    fun label(capability: AssistantCapability): String = when (capability) {
        AssistantCapability.MCP_CLIENT -> "Extra AI tools"
        AssistantCapability.LINUX_ENVIRONMENT -> "Linux environment"
        AssistantCapability.BACKGROUND_AUTOMATION -> "Background automation"
        AssistantCapability.MAILBOX_ACCESS -> "Email access"
        AssistantCapability.SMS_ACCESS -> "Text-message access"
        AssistantCapability.CLOUD_TRANSFER -> "Cloud file transfer"
        AssistantCapability.GENERATED_UI -> "Generated screens"
        AssistantCapability.SKILL_SCRIPTS -> "Skill scripts"
        AssistantCapability.KAI_BUILD -> "Kai Build"
    }
}
