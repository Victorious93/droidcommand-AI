package ai.droidcommand.activity

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One unit of work by one actor. [start] records the beginning; exactly one of [finish], [fail] or
 * [cancel] ends it (a second ending call is ignored, so a late callback cannot rewrite history).
 * [note] adds a free progress line (already redacted by this class).
 *
 * The caller decides what counts as the end. For a chat turn that is when the model's final answer
 * arrives; for a fleet job it is the coordinator's terminal state. This type never infers it.
 */
class ActivityRun private constructor(
    val actor: ActivityActor,
    val runId: String,
    private val log: ActivityLog,
    private val clock: () -> Long,
) {
    private val ended = AtomicBoolean(false)

    val isEnded: Boolean get() = ended.get()

    fun note(text: String) = emit(ActivityKind.NOTE, ActivityRedactor.text(text, 200), null)

    /**
     * For a request that is waiting on the owner outside [ActivityApprovalPrompt], such as a fleet app's
     * COMPANION_EXEC request that has its own Allow screen. [detail] is what is being asked.
     */
    fun awaitingOwner(summary: String, detail: String? = null) =
        emit(ActivityKind.NEEDS_APPROVAL, ActivityRedactor.text(summary, 200), detail?.let { ActivityRedactor.text(it, 200) })

    /** The owner's answer to an [awaitingOwner] wait. */
    fun ownerDecided(allowed: Boolean, summary: String = if (allowed) "You allowed it" else "You declined it") =
        emit(if (allowed) ActivityKind.APPROVAL_GRANTED else ActivityKind.APPROVAL_DENIED, ActivityRedactor.text(summary, 200), null)

    /**
     * Runs [block] with this run as the current one on the calling thread, so an approval dialog raised
     * inside it (see [ActivityApprovalPrompt]) is attributed to this run. Use it around a blocking call that
     * is not already going through an [ActivityRecordingToolRunner].
     */
    fun <T> inScope(block: () -> T): T = ActivityScope.within(scope(), block)

    fun finish(summary: String = "Finished") = end(ActivityKind.RUN_FINISHED, summary)

    fun fail(reason: String) = end(ActivityKind.RUN_FAILED, reason)

    fun cancel(summary: String = "Stopped") = end(ActivityKind.RUN_CANCELLED, summary)

    /** The scope a tool call for this run executes under. */
    internal fun scope() = ActivityScope.Current(actor, runId)

    private fun end(kind: ActivityKind, summary: String) {
        if (ended.compareAndSet(false, true)) emit(kind, ActivityRedactor.text(summary, 200), null)
    }

    private fun emit(kind: ActivityKind, summary: String, detail: String?) {
        runCatching { log.append(ActivityEvent(actor, runId, kind, summary, detail, clock())) }
    }

    companion object {
        fun start(
            actor: ActivityActor,
            summary: String,
            log: ActivityLog,
            clock: () -> Long = System::currentTimeMillis,
            runId: String = UUID.randomUUID().toString(),
        ): ActivityRun {
            val run = ActivityRun(actor, runId, log, clock)
            run.emit(ActivityKind.RUN_STARTED, ActivityRedactor.text(summary, 200), null)
            return run
        }
    }
}
