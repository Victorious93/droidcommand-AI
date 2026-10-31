package ai.droidcommand.config

/**
 * Persisted, fail-closed gates for Kai-integration capabilities that must
 * stay off until the user enables them (owner prompt § "Background work,
 * personal tools and storage").
 *
 * This object never includes, reads, or writes `externalControlEnabled`
 * (that flag lives only on `core-remotecontrol.RemoteControlConfig` and
 * stays **false** by default) and never waives `confirmation_required`
 * for destructive actions. Importing a file that happens to mention
 * those keys is ignored.
 *
 * Every flag defaults to false. [load] re-reads [ConfigSource] each call
 * and never caches, matching [ConfigReader].
 */
enum class AssistantCapability(val wireName: String) {
    MCP_CLIENT("mcpClientEnabled"),
    LINUX_ENVIRONMENT("linuxEnvironmentEnabled"),
    BACKGROUND_AUTOMATION("backgroundAutomationEnabled"),
    MAILBOX_ACCESS("mailboxAccessEnabled"),
    SMS_ACCESS("smsAccessEnabled"),
    CLOUD_TRANSFER("cloudTransferEnabled"),
    GENERATED_UI("generatedUiEnabled"),
    SKILL_SCRIPTS("skillScriptsEnabled"),
    KAI_BUILD("kaiBuildEnabled"),
    BRAIN("brainEnabled"),
    BOT_MAKER("botMakerEnabled"),
}

data class AssistantCapabilitySettings(
    val mcpClientEnabled: Boolean = false,
    val linuxEnvironmentEnabled: Boolean = false,
    val backgroundAutomationEnabled: Boolean = false,
    val mailboxAccessEnabled: Boolean = false,
    val smsAccessEnabled: Boolean = false,
    val cloudTransferEnabled: Boolean = false,
    val generatedUiEnabled: Boolean = false,
    val skillScriptsEnabled: Boolean = false,
    val kaiBuildEnabled: Boolean = false,
    val brainEnabled: Boolean = false,
    val botMakerEnabled: Boolean = false,
) {
    fun isEnabled(capability: AssistantCapability): Boolean = when (capability) {
        AssistantCapability.MCP_CLIENT -> mcpClientEnabled
        AssistantCapability.LINUX_ENVIRONMENT -> linuxEnvironmentEnabled
        AssistantCapability.BACKGROUND_AUTOMATION -> backgroundAutomationEnabled
        AssistantCapability.MAILBOX_ACCESS -> mailboxAccessEnabled
        AssistantCapability.SMS_ACCESS -> smsAccessEnabled
        AssistantCapability.CLOUD_TRANSFER -> cloudTransferEnabled
        AssistantCapability.GENERATED_UI -> generatedUiEnabled
        AssistantCapability.SKILL_SCRIPTS -> skillScriptsEnabled
        AssistantCapability.KAI_BUILD -> kaiBuildEnabled
        AssistantCapability.BRAIN -> brainEnabled
        AssistantCapability.BOT_MAKER -> botMakerEnabled
    }

    fun disabledReason(capability: AssistantCapability): String =
        "${capability.wireName} is disabled until enabled in Settings; new work will not start."

    fun enabledCapabilities(): List<AssistantCapability> =
        AssistantCapability.entries.filter { isEnabled(it) }

    companion object {
        val DISABLED = AssistantCapabilitySettings()

        fun load(source: ConfigSource): AssistantCapabilitySettings {
            val reader = ConfigReader(source)
            return AssistantCapabilitySettings(
                mcpClientEnabled = reader.optionalBoolean(ConfigKeys.MCP_CLIENT_ENABLED),
                linuxEnvironmentEnabled = reader.optionalBoolean(ConfigKeys.LINUX_ENVIRONMENT_ENABLED),
                backgroundAutomationEnabled = reader.optionalBoolean(ConfigKeys.BACKGROUND_AUTOMATION_ENABLED),
                mailboxAccessEnabled = reader.optionalBoolean(ConfigKeys.MAILBOX_ACCESS_ENABLED),
                smsAccessEnabled = reader.optionalBoolean(ConfigKeys.SMS_ACCESS_ENABLED),
                cloudTransferEnabled = reader.optionalBoolean(ConfigKeys.CLOUD_TRANSFER_ENABLED),
                generatedUiEnabled = reader.optionalBoolean(ConfigKeys.GENERATED_UI_ENABLED),
                skillScriptsEnabled = reader.optionalBoolean(ConfigKeys.SKILL_SCRIPTS_ENABLED),
                kaiBuildEnabled = reader.optionalBoolean(ConfigKeys.KAI_BUILD_ENABLED),
                brainEnabled = reader.optionalBoolean(ConfigKeys.BRAIN_ENABLED),
                botMakerEnabled = reader.optionalBoolean(ConfigKeys.BOT_MAKER_ENABLED),
            )
        }
    }
}
