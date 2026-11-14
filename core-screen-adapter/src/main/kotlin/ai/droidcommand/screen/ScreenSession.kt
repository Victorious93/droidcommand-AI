package ai.droidcommand.screen

/** What must be torn down whenever a session ends for ANY reason. Each step is idempotent; the session is not CLOSED until all are acknowledged. */
enum class Cleanup { STOP_TARGET_SERVER_PROCESS, RELEASE_DECODER, RELEASE_ALL_INPUT_POINTERS, CLOSE_CONNECTIONS, DELETE_TARGET_TEMP_FILES, FORGET_SESSION_KEYS }

enum class EndReason { OWNER_STOPPED, TARGET_DISCONNECTED, NETWORK_LOST, REVOKED, CANCELLED, APP_BACKGROUNDED, POLICY_CHANGED, ERROR }

sealed class SessionState {
    object Idle : SessionState()
    object Connecting : SessionState()
    object Streaming : SessionState()

    /** Teardown ordered but not all steps acknowledged. UI must say "stopping", never "stopped". */
    data class Stopping(val reason: EndReason, val pending: Set<Cleanup>) : SessionState()
    data class Closed(val reason: EndReason) : SessionState()

    /** An error is reported as an error; there is no simulated success state. */
    data class Failed(val message: String, val pending: Set<Cleanup>) : SessionState()
}

/**
 * Session lifecycle with real teardown accounting. Pure state: the Android layer performs each [Cleanup] and calls
 * [cleanupDone]. Rotation is NOT an end event (the stream is re-negotiated by the caller), while backgrounding, revocation,
 * disconnect, cancellation and network loss all are.
 */
class ScreenSession(private val settings: () -> ScreenAdapterSettings, private val target: PairedTarget) {
    var state: SessionState = SessionState.Idle
        private set

    fun start(foregroundAcquired: Boolean): ScreenDecision {
        if (state != SessionState.Idle && state !is SessionState.Closed && state !is SessionState.Failed) return ScreenDecision.Denied("A session is already active.")
        val d = ScreenAdapterPolicy.decide(settings(), target, ScreenCapability.VIEW)
        if (d is ScreenDecision.Denied) return d
        if (!foregroundAcquired) return ScreenDecision.Denied("Another foreground UI task is using this phone.") // shared gate with Pilot/worker jobs
        state = SessionState.Connecting
        return ScreenDecision.Allowed
    }

    fun connected() {
        if (state == SessionState.Connecting) state = SessionState.Streaming
    }

    /** Input is re-checked against LIVE policy on every call; a revoked grant stops input immediately even mid-stream. */
    fun mayInject(): ScreenDecision =
        if (state != SessionState.Streaming) ScreenDecision.Denied("No active stream.") else ScreenAdapterPolicy.decide(settings(), target, ScreenCapability.INPUT)

    fun policyChanged(): Set<Cleanup> =
        if (state == SessionState.Streaming && ScreenAdapterPolicy.decide(settings(), target, ScreenCapability.VIEW) is ScreenDecision.Denied) end(EndReason.POLICY_CHANGED) else emptySet()

    fun end(reason: EndReason): Set<Cleanup> {
        val s = state
        if (s is SessionState.Idle || s is SessionState.Closed) return emptySet()
        val pending = (s as? SessionState.Stopping)?.pending ?: (s as? SessionState.Failed)?.pending ?: Cleanup.values().toSet()
        state = SessionState.Stopping((s as? SessionState.Stopping)?.reason ?: reason, pending)
        return pending
    }

    fun fail(message: String): Set<Cleanup> {
        val all = Cleanup.values().toSet()
        state = SessionState.Failed(message, all)
        return all
    }

    fun cleanupDone(step: Cleanup) {
        when (val s = state) {
            is SessionState.Stopping -> {
                val left = s.pending - step
                state = if (left.isEmpty()) SessionState.Closed(s.reason) else s.copy(pending = left)
            }
            is SessionState.Failed -> state = s.copy(pending = s.pending - step)
            else -> Unit
        }
    }

    fun rotated() { /* no state change: caller renegotiates stream size; session continues */ }
}
