package ai.droidcommand.app.ui.chat

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

    companion object {
        const val MISSING_MESSAGE =
            "The on-device model you selected is no longer on this device (its file was removed or cannot be read). " +
                "Nothing was sent. Pick a model in Models, or choose a cloud provider here."

        /**
         * [selectedLocalId] is the saved on-device choice (null: cloud). [installedIds] are the models whose files are
         * present now. [slashCommandNamesCloud] is true when this message starts with a provider slash command, which
         * is the owner explicitly sending that one message to that provider.
         */
        fun decide(selectedLocalId: String?, installedIds: Set<String>, slashCommandNamesCloud: Boolean): ChatRoute = when {
            slashCommandNamesCloud -> Cloud
            selectedLocalId == null -> Cloud
            selectedLocalId in installedIds -> OnDevice(selectedLocalId)
            else -> OnDeviceMissing(MISSING_MESSAGE)
        }
    }
}
