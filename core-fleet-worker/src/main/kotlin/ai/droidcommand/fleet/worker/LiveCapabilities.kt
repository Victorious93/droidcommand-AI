package ai.droidcommand.fleet.worker

/** Maps what VictorSuite / VictorSMS report right now to the protocol 1.1 capability entries (live, never from a flag). */
object LiveCapabilities {
    private fun op(s: BrokerStatus, rootNeeded: Boolean = false): OperationState {
        val ready = s.state == "ready" && (!rootNeeded || s.rootAllowed)
        val state = if (ready) "ready" else if (rootNeeded && s.state == "ready") "permission_needed" else s.state
        return OperationState(available = ready, granted = ready, ownerInteractionRequired = ready, reason = if (ready) null else (s.detail ?: state.replace('_', ' ')),
            status = state, detail = if (ready) null else s.detail)
    }

    fun operations(shell: BrokerStatus?, sms: BrokerStatus?, email: BrokerStatus?): Map<String, OperationState> {
        val missing = BrokerStatus.of("not_installed", "VictorSuite is not installed")
        val sh = shell ?: missing
        val noSms = BrokerStatus.of("not_installed", "VictorSMS is not installed")
        return mapOf(
            LiveProtocol.OP_TERMINAL to op(sh), LiveProtocol.OP_ONESHOT to op(sh), LiveProtocol.OP_ROOT to op(sh, rootNeeded = true),
            LiveProtocol.OP_DRAFT_SMS to op(sms ?: noSms), LiveProtocol.OP_DRAFT_EMAIL to op(email ?: noSms),
        )
    }
}
