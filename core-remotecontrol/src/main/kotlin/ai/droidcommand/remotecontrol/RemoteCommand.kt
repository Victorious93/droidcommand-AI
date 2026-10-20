package ai.droidcommand.remotecontrol

/**
 * Runtime view of a frozen [FleetRemoteEnvelope] after mapping resolution.
 * Wire format is [FleetRemoteEnvelope] (same field names on every class).
 */
data class RemoteCommand(
    val target: NodeRef,
    val actionRef: String,
    val args: Map<String, String> = emptyMap(),
    val nonce: String,
    val timestampMs: Long,
    val requestId: String? = null,
    val ttlMs: Long = FleetRemoteEnvelope.DEFAULT_TTL_MS,
) {
    init {
        require(actionRef.isNotBlank()) { "actionRef is required" }
        require(nonce.matches(FleetRemoteEnvelope.NONCE_128_BIT)) { "nonce must be 128-bit hex" }
        require(args.size <= FleetRemoteEnvelope.MAX_ARGS) {
            "args exceeds ${FleetRemoteEnvelope.MAX_ARGS} entries"
        }
    }

    companion object {
        const val OPERATION = FleetRemoteEnvelope.OP_REMOTE_COMMAND
    }
}
