package ai.droidcommand.remotecontrol

/**
 * Live privileged / RustDesk / fleet-node execution seam.
 *
 * TODO: implement device-admin wipe/lock, root/Shizuku power-off, RustDesk
 * full-screen handoff, and class-specific remote.command / macro.step send.
 * This skeleton must not grow those calls.
 */
interface RemoteActionExecutor {
    fun execute(plan: RemoteDispatchPlan): RemoteActionResult
}

sealed class RemoteActionResult {
    data class NotImplemented(val reason: String) : RemoteActionResult()
    data class Denied(val reason: String) : RemoteActionResult()
}

/**
 * Honest stub. Always returns [RemoteActionResult.NotImplemented]. Never
 * wipes, power-offs, locks, starts RustDesk, or sends a live fleet command.
 */
class StubRemoteActionExecutor : RemoteActionExecutor {
    override fun execute(plan: RemoteDispatchPlan): RemoteActionResult {
        val target = plan.mapping?.targetNodeId
            ?: plan.macro?.steps?.firstOrNull()?.target?.nodeId
            ?: "unknown"
        val label = plan.mapping?.action?.name
            ?: plan.macro?.let { "macro:${it.id}" }
            ?: "command"
        return RemoteActionResult.NotImplemented(
            "Remote Control skeleton: live $label (${plan.toolName}) on $target is not implemented. " +
                "No wipe, power-off, lock, RustDesk session, or fleet privileged action is started.",
        )
    }
}

/**
 * Inbound seam. Identity is [SourceIdentity], never [TriggerInfo].
 */
interface RemoteCommandInbound {
    fun onEnvelope(rawJson: String, source: SourceIdentity): RemoteDispatchResult
}

class ValidatingRemoteCommandInbound(
    private val dispatcher: RemoteCommandDispatcher,
) : RemoteCommandInbound {
    override fun onEnvelope(rawJson: String, source: SourceIdentity): RemoteDispatchResult =
        dispatcher.dispatch(rawJson, source)
}

class UnwiredRemoteCommandInbound : RemoteCommandInbound {
    override fun onEnvelope(rawJson: String, source: SourceIdentity): RemoteDispatchResult =
        RemoteDispatchResult.Rejected(
            "Fleet remote inbound is not wired. " +
                "Live send for ${FleetRemoteEnvelope.OP_REMOTE_COMMAND} is a TODO.",
        )
}
