package ai.droidcommand.activity

/**
 * What happened. A run is one bot (or Pilot/fleet job) doing one piece of work: it starts, takes
 * zero or more tool steps, may stop to wait for the owner, and ends in exactly one of the three
 * terminal kinds.
 */
enum class ActivityKind {
    RUN_STARTED,
    TOOL_STARTED,
    TOOL_FINISHED,
    TOOL_FAILED,
    NEEDS_APPROVAL,
    APPROVAL_GRANTED,
    APPROVAL_DENIED,
    NOTE,
    RUN_FINISHED,
    RUN_FAILED,
    RUN_CANCELLED,
    ;

    val isTerminal: Boolean get() = this == RUN_FINISHED || this == RUN_FAILED || this == RUN_CANCELLED
}

/** Who is doing the work: a helper bot, Pilot, a fleet job. [id] is stable; [name] is only for display. */
data class ActivityActor(val id: String, val name: String)

/**
 * One line of the activity feed. [summary] and [detail] are already redacted and bounded by
 * [ActivityRedactor] before they get here; nothing in this module stores a raw tool argument or
 * raw tool output. [seq] is assigned by the [ActivityLog] on append (monotonic per log), so order
 * never depends on clock resolution.
 */
data class ActivityEvent(
    val actor: ActivityActor,
    val runId: String,
    val kind: ActivityKind,
    val summary: String,
    val detail: String? = null,
    val atMillis: Long,
    val seq: Long = 0,
)
