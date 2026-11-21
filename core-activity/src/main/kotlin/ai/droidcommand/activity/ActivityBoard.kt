package ai.droidcommand.activity

/**
 * What a bot is doing *right now*, in words a person uses. Derived only from recorded events;
 * nothing is guessed. [STALLED] means "working, but nothing has been reported for a while", which
 * is not the same as failed: the bot may be inside one long tool call.
 */
enum class ActivityStatus(val label: String) {
    WAITING_FOR_YOU("Waiting for you"),
    WORKING("Working"),
    STALLED("No update for a while"),
    FAILED("Failed"),
    DONE("Done"),
    STOPPED("Stopped"),
}

/** One row of the "all bots" overview: the newest run of one actor. */
data class ActorActivity(
    val actor: ActivityActor,
    val status: ActivityStatus,
    val runId: String,
    /** The tool call in flight, if any. */
    val currentStep: String?,
    /** What the owner is being asked, if [status] is [ActivityStatus.WAITING_FOR_YOU]. */
    val pendingApproval: String?,
    /** The newest event's one-line summary. */
    val lastSummary: String,
    val startedAtMillis: Long,
    val lastEventAtMillis: Long,
    val steps: Int,
    val failedSteps: Int,
)

object ActivityBoard {
    /** No event for this long while working shows as [ActivityStatus.STALLED]. */
    const val DEFAULT_STALL_MS = 10 * 60 * 1000L

    /**
     * One row per actor, describing that actor's newest run. Rows that need the owner come first,
     * then working ones, then finished ones, each group newest first — so the screen answers "what
     * needs me?" before "what is busy?".
     */
    fun overview(events: List<ActivityEvent>, nowMillis: Long, stallAfterMs: Long = DEFAULT_STALL_MS): List<ActorActivity> =
        events.groupBy { it.actor.id }.values
            .map { actorEvents -> summarize(actorEvents, nowMillis, stallAfterMs) }
            .sortedWith(compareBy<ActorActivity> { it.status.ordinal }.thenByDescending { it.lastEventAtMillis })

    /** Newest first. [runId] null means the actor's newest run. */
    fun timeline(events: List<ActivityEvent>, actorId: String, runId: String? = null): List<ActivityEvent> {
        val mine = events.filter { it.actor.id == actorId }
        val run = runId ?: mine.maxByOrNull { it.seq }?.runId ?: return emptyList()
        return mine.filter { it.runId == run }.sortedByDescending { it.seq }
    }

    private fun summarize(actorEvents: List<ActivityEvent>, now: Long, stallAfterMs: Long): ActorActivity {
        val newest = actorEvents.maxBy { it.seq }
        val run = actorEvents.filter { it.runId == newest.runId }.sortedBy { it.seq }
        val last = run.last()

        // A tool step is "in flight" while its TOOL_STARTED has no later TOOL_FINISHED/TOOL_FAILED.
        var inFlight: String? = null
        var pending: String? = null
        var steps = 0
        var failed = 0
        for (e in run) {
            when (e.kind) {
                ActivityKind.TOOL_STARTED -> {
                    inFlight = e.summary
                    steps++
                }
                ActivityKind.TOOL_FINISHED -> inFlight = null
                ActivityKind.TOOL_FAILED -> {
                    inFlight = null
                    failed++
                }
                ActivityKind.NEEDS_APPROVAL -> pending = e.detail ?: e.summary
                ActivityKind.APPROVAL_GRANTED, ActivityKind.APPROVAL_DENIED -> pending = null
                else -> Unit
            }
        }

        val status = when (last.kind) {
            ActivityKind.RUN_FINISHED -> ActivityStatus.DONE
            ActivityKind.RUN_FAILED -> ActivityStatus.FAILED
            ActivityKind.RUN_CANCELLED -> ActivityStatus.STOPPED
            ActivityKind.NEEDS_APPROVAL -> ActivityStatus.WAITING_FOR_YOU
            else -> if (now - last.atMillis > stallAfterMs) ActivityStatus.STALLED else ActivityStatus.WORKING
        }
        val ended = status == ActivityStatus.DONE || status == ActivityStatus.FAILED || status == ActivityStatus.STOPPED
        return ActorActivity(
            actor = last.actor,
            status = status,
            runId = last.runId,
            currentStep = if (ended) null else inFlight,
            pendingApproval = if (status == ActivityStatus.WAITING_FOR_YOU) pending else null,
            lastSummary = last.summary,
            startedAtMillis = run.first().atMillis,
            lastEventAtMillis = last.atMillis,
            steps = steps,
            failedSteps = failed,
        )
    }
}
