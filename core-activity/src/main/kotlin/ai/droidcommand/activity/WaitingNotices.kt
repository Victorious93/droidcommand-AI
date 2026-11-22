package ai.droidcommand.activity

/**
 * Who is waiting for the owner right now, kept up to date from the events as they arrive. This is the
 * pure decision behind a "something is waiting for you" notification; showing the notification is the
 * app's job. It holds one entry per run, so a bot that asks twice in a row is one notification, and an
 * answer, a decline or the end of the run removes it.
 */
class WaitingNotices {
    data class Waiting(val runId: String, val actorName: String, val sinceMillis: Long)

    private val waiting = LinkedHashMap<String, Waiting>()

    /** Feeds one event. Returns true when the list of waiting runs changed. */
    @Synchronized
    fun onEvent(event: ActivityEvent): Boolean = when {
        event.kind == ActivityKind.NEEDS_APPROVAL ->
            if (waiting.containsKey(event.runId)) {
                false
            } else {
                waiting[event.runId] = Waiting(event.runId, event.actor.name, event.atMillis)
                true
            }
        event.kind == ActivityKind.APPROVAL_GRANTED || event.kind == ActivityKind.APPROVAL_DENIED || event.kind.isTerminal ->
            waiting.remove(event.runId) != null
        else -> false
    }

    /** Oldest first. */
    @Synchronized
    fun current(): List<Waiting> = waiting.values.toList()
}
