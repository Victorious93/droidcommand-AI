package ai.droidcommand.remotecontrol

/**
 * One step of a [Macro]. Wire op is [FleetRemoteEnvelope.OP_MACRO_STEP].
 * [destructive] on a step is classified independently of any envelope flag.
 */
data class MacroStep(
    val id: String,
    val target: NodeRef,
    val actionRef: String,
    val args: Map<String, String> = emptyMap(),
    val destructive: Boolean = false,
    val onError: String = FleetRemoteEnvelope.ON_ERROR_ABORT,
) {
    init {
        require(id.matches(RemoteCommandMapping.ID)) { "step id must match ${RemoteCommandMapping.ID}: '$id'" }
        require(actionRef.isNotBlank()) { "actionRef is required" }
        require(args.size <= FleetRemoteEnvelope.MAX_ARGS) {
            "args exceeds ${FleetRemoteEnvelope.MAX_ARGS} entries"
        }
        require(
            onError == FleetRemoteEnvelope.ON_ERROR_ABORT ||
                onError == FleetRemoteEnvelope.ON_ERROR_CONTINUE,
        ) { "onError must be abort or continue" }
    }

    companion object {
        const val OPERATION = FleetRemoteEnvelope.OP_MACRO_STEP
    }
}

enum class MacroTriggerKind {
    VOICE,
    SMS_COMMAND_ID,
    SCHEDULE,
    MANUAL,
}

data class MacroTrigger(
    val kind: MacroTriggerKind,
    val voicePhrase: String? = null,
    val smsCommandId: String? = null,
    val scheduleExpr: String? = null,
) {
    val normalizedVoicePhrase: String? =
        voicePhrase?.let { VoicePhraseMatcher.normalize(it) }?.takeIf { it.isNotEmpty() }

    init {
        when (kind) {
            MacroTriggerKind.VOICE ->
                require(!normalizedVoicePhrase.isNullOrBlank()) { "VOICE trigger needs a voice phrase" }
            MacroTriggerKind.SMS_COMMAND_ID -> {
                require(!smsCommandId.isNullOrBlank()) { "SMS trigger needs a commandId" }
                require(smsCommandId.matches(RemoteCommandMapping.ID)) {
                    "smsCommandId must match ${RemoteCommandMapping.ID}: '$smsCommandId'"
                }
            }
            MacroTriggerKind.SCHEDULE ->
                require(!scheduleExpr.isNullOrBlank()) { "SCHEDULE trigger needs a schedule expression" }
            MacroTriggerKind.MANUAL -> Unit
        }
    }
}

/**
 * Ordered sequence of fleet actions. A macro with any destructive step
 * requires approval at run and never auto-approves (including YOLO).
 */
data class Macro(
    val id: String,
    val displayName: String,
    val steps: List<MacroStep>,
    val trigger: MacroTrigger,
) {
    val hasDestructiveStep: Boolean = steps.any { ActionRefClassifier.stepIsDestructive(it) }

    init {
        require(id.matches(RemoteCommandMapping.ID)) { "macro id must match ${RemoteCommandMapping.ID}: '$id'" }
        require(displayName.isNotBlank()) { "displayName is required" }
        require(steps.isNotEmpty()) { "macro needs at least one step" }
        val stepIds = steps.map { it.id }
        require(stepIds.size == stepIds.toSet().size) { "duplicate step id in macro '$id'" }
    }

    fun toolName(): String =
        if (hasDestructiveStep) RemoteControlToolNames.DESTRUCTIVE else RemoteControlToolNames.MACRO
}
