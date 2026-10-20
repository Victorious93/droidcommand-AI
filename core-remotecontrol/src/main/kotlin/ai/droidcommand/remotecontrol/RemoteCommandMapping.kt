package ai.droidcommand.remotecontrol

/**
 * One user-configured mapping from a trigger (voice phrase and/or SMS commandId)
 * onto a target node + action. Validated at construction: a mapping must have at
 * least one trigger, delivery mode must match the action, and WIPE / POWER_OFF /
 * LOCK cannot be stored as non-destructive.
 */
data class RemoteCommandMapping(
    val id: String,
    val displayName: String,
    val voicePhrase: String? = null,
    val smsCommandId: String? = null,
    val targetNodeId: String,
    val action: RemoteAction,
    val deliveryMode: DeliveryMode,
    val destructive: Boolean,
    val extras: Map<String, String> = emptyMap(),
) {
    val effectiveDestructive: Boolean = DestructiveRemoteActions.isDestructive(action, destructive)

    val normalizedVoicePhrase: String? = voicePhrase?.let { VoicePhraseMatcher.normalize(it) }?.takeIf { it.isNotEmpty() }

    init {
        require(id.matches(ID)) { "mapping id must match $ID: '$id'" }
        require(displayName.isNotBlank()) { "displayName is required" }
        require(targetNodeId.isNotBlank()) { "targetNodeId is required" }
        require(!voicePhrase.isNullOrBlank() || !smsCommandId.isNullOrBlank()) {
            "mapping needs a voice phrase and/or an SMS commandId"
        }
        if (smsCommandId != null) {
            require(smsCommandId.matches(ID)) { "smsCommandId must match $ID: '$smsCommandId'" }
        }
        require(deliveryMode == action.allowedDelivery) {
            "${action.name} only supports ${action.allowedDelivery}, not $deliveryMode"
        }
        if (action.destructiveByDefinition) {
            require(destructive) { "${action.name} cannot be stored as non-destructive" }
        }
    }

    fun toolName(): String = when {
        effectiveDestructive && action == RemoteAction.WIPE -> RemoteControlToolNames.WIPE
        effectiveDestructive && action == RemoteAction.POWER_OFF -> RemoteControlToolNames.POWER_OFF
        effectiveDestructive && action == RemoteAction.LOCK -> RemoteControlToolNames.LOCK
        effectiveDestructive -> RemoteControlToolNames.DESTRUCTIVE
        deliveryMode == DeliveryMode.FULL_SCREEN_CONTROL -> RemoteControlToolNames.SCREEN
        else -> RemoteControlToolNames.COMMAND
    }

    fun toRemoteCommand(
        target: NodeRef,
        nonce: String,
        timestampMs: Long,
        extraArgs: Map<String, String> = emptyMap(),
    ): RemoteCommand = RemoteCommand(
        target = target,
        actionRef = id,
        args = extras + extraArgs,
        nonce = nonce,
        timestampMs = timestampMs,
    )

    companion object {
        val ID = Regex("""^[a-z0-9][a-z0-9_-]*$""")
    }
}

object RemoteControlToolNames {
    const val COMMAND = "remote_command"
    const val SCREEN = "remote_screen_control"
    const val MACRO = "remote_macro"
    const val WIPE = "remote_wipe"
    const val POWER_OFF = "remote_power_off"
    const val LOCK = "remote_lock"
    const val DESTRUCTIVE = "remote_destructive"

    val ALL: Set<String> = setOf(COMMAND, SCREEN, MACRO, WIPE, POWER_OFF, LOCK, DESTRUCTIVE)
}
