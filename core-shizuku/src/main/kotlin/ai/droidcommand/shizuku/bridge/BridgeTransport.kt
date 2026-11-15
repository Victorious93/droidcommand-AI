package ai.droidcommand.shizuku.bridge

enum class BridgeAccess { NONE, SCOPED, LEGACY }

/** Result of capability negotiation. [available] false means the service is not running or speaks another contract. */
data class BridgeStatus(
    val available: Boolean,
    val apiVersion: Int = 0,
    val execMode: String? = null,
    val access: BridgeAccess = BridgeAccess.NONE,
    val held: Set<BridgeCapability> = emptySet(),
    val supportedOperations: Set<String> = emptySet(),
    val grantRemainingMs: Long? = null,
    val servicePolicy: BridgeBackend = BridgeBackend.ANY,
) {
    companion object {
        val UNAVAILABLE = BridgeStatus(available = false)
    }
}

data class BridgeRequestSpec(
    val requestId: String,
    val operation: String,
    val args: Map<String, Any>,
    val backend: BridgeBackend = BridgeBackend.ANY,
    /** Correlation only. See [BridgeContract]. */
    val remoteTaskId: String? = null,
)

data class BridgeResponse(
    val requestId: String,
    /** One of OK, ERROR, CANCELLED. */
    val status: String,
    val error: BridgeError? = null,
    val message: String? = null,
    val data: Map<String, Any?> = emptyMap(),
    val durationMs: Long = 0,
    val execMode: String? = null,
) {
    val ok: Boolean get() = status == "OK"
}

/** An in-flight request that can be waited on or cancelled. */
interface BridgeCall {
    val requestId: String

    /** Blocks up to [timeoutMs]; returns null on local timeout (the caller then cancels). */
    fun await(timeoutMs: Long): BridgeResponse?
    fun cancel(): Boolean
}

/**
 * Platform seam. The Android app implements it over the Victorious Bridge SDK (`VictoriousBridge.negotiate/submit`);
 * JVM tests and the CLI use fakes or [UnavailableBridgeTransport]. Nothing else in DCA touches the bridge.
 */
interface BridgeTransport {
    fun negotiate(): BridgeStatus
    fun submit(request: BridgeRequestSpec): BridgeCall
}

object UnavailableBridgeTransport : BridgeTransport {
    override fun negotiate(): BridgeStatus = BridgeStatus.UNAVAILABLE
    override fun submit(request: BridgeRequestSpec): BridgeCall = object : BridgeCall {
        override val requestId = request.requestId
        override fun await(timeoutMs: Long) = BridgeResponse(requestId, "ERROR", BridgeError.NOT_ATTACHED, "Victorious Bridge is not available")
        override fun cancel() = false
    }
}
