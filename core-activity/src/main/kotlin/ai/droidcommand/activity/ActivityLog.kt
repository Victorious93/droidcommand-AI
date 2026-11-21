package ai.droidcommand.activity

/**
 * The live "what is it doing" feed. This is **not** `core-security`'s `AuditLog` and must not be
 * merged with it: the audit log is append-only and fails closed when full (a full audit log
 * makes sensitive actions get refused), which is exactly wrong for a high-volume progress feed.
 * This log does the opposite: it is bounded and drops the *oldest* entries, and a failure to
 * record here never changes what a tool does. Security decisions keep going to the audit log.
 */
interface ActivityLog {
    /** Stores [event], assigning its [ActivityEvent.seq]. Never throws. */
    fun append(event: ActivityEvent)

    /** Oldest first. */
    fun snapshot(): List<ActivityEvent>

    /** Called on the appending thread after each append; the returned function removes the listener. */
    fun addListener(listener: (ActivityEvent) -> Unit): () -> Unit
}

/**
 * In-memory ring: keeps the newest [capacity] events. Contents do not survive a process restart,
 * and the UI says so rather than showing an empty feed as if nothing had happened.
 */
class InMemoryActivityLog(private val capacity: Int = 2_000) : ActivityLog {
    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val lock = Any()
    private val events = ArrayDeque<ActivityEvent>()
    private var nextSeq = 1L
    private val listeners = mutableListOf<(ActivityEvent) -> Unit>()

    override fun append(event: ActivityEvent) {
        val stored: ActivityEvent
        val toNotify: List<(ActivityEvent) -> Unit>
        synchronized(lock) {
            stored = event.copy(seq = nextSeq++)
            events.addLast(stored)
            while (events.size > capacity) events.removeFirst()
            toNotify = listeners.toList()
        }
        // Outside the lock so a slow or re-entrant listener cannot block other appenders.
        toNotify.forEach { listener -> runCatching { listener(stored) } }
    }

    override fun snapshot(): List<ActivityEvent> = synchronized(lock) { events.toList() }

    override fun addListener(listener: (ActivityEvent) -> Unit): () -> Unit {
        synchronized(lock) { listeners += listener }
        return { synchronized(lock) { listeners -= listener } }
    }
}
