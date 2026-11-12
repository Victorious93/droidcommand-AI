package ai.droidcommand.app.ui.chat

import ai.droidcommand.llm.pchost.PcHostEndpoint
import ai.droidcommand.llm.pchost.PcHostErrors
import ai.droidcommand.llm.pchost.PcHostKind
import ai.droidcommand.llm.pchost.PcHostTarget

/**
 * Where one chat message goes. Pure, so the rule "an on-device choice never turns into a cloud request on its own" is
 * JVM-tested rather than buried in the view model.
 */
sealed interface ChatRoute {
    /** The selected on-device model answers. Nothing about this turn uses the network. */
    data class OnDevice(val modelId: String) : ChatRoute

    /** The cloud provider answers: none on-device is selected, or this one message named a provider with a slash command. */
    data object Cloud : ChatRoute

    /**
     * An on-device model is selected but its file is gone. The message is NOT sent to the cloud provider instead: the
     * owner chose to keep this chat on the device, so they are told and decide.
     */
    data class OnDeviceMissing(val message: String) : ChatRoute

    /** The model server on the owner's PC answers. The message goes to that PC and nowhere else. */
    data class PcHosted(val target: PcHostTarget) : ChatRoute

    /**
     * A PC server is selected but has no usable address or model saved. As with [OnDeviceMissing], the message is NOT
     * sent to the cloud provider instead.
     */
    data class PcHostNotReady(val message: String) : ChatRoute

    companion object {
        fun pcNotReady(kind: PcHostKind): String =
            "${PcHostErrors.PREFIX}${kind.label} is selected for Chat, but no address and model are saved for it. " +
                "Nothing was sent. Open Settings, PC-hosted models, or choose another model here."

        const val MISSING_MESSAGE =
            "The on-device model you selected is no longer on this device (its file was removed or cannot be read). " +
                "Nothing was sent. Pick a model in Models, or choose a cloud provider here."

        /**
         * [selectedLocalId] is the saved on-device choice (null: cloud). [installedIds] are the models whose files are
         * present now. [slashCommandNamesCloud] is true when this message starts with a provider slash command, which
         * is the owner explicitly sending that one message to that provider. [pcHost] is the selected PC server kind
         * (null: none) and [pcHostEndpoint] what is saved for it; a PC choice wins over an on-device one (the screens
         * that write them clear the other).
         */
        fun decide(
            selectedLocalId: String?,
            installedIds: Set<String>,
            slashCommandNamesCloud: Boolean,
            pcHost: PcHostKind? = null,
            pcHostEndpoint: PcHostEndpoint? = null,
        ): ChatRoute = when {
            slashCommandNamesCloud -> Cloud
            pcHost != null -> pcHostEndpoint?.target(pcHost)?.let(::PcHosted) ?: PcHostNotReady(pcNotReady(pcHost))
            selectedLocalId == null -> Cloud
            selectedLocalId in installedIds -> OnDevice(selectedLocalId)
            else -> OnDeviceMissing(MISSING_MESSAGE)
        }
    }
}
