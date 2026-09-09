package ai.droidcommand.agent

/**
 * Ties a [MacroStore] to a [MacroExecutor] via a [Scheduler]: makes a saved
 * macro actually run on a real recurring cadence, closing the scheduling
 * half of ROADMAP-127 that [MacroExecutor] (playback only) and
 * [MacroStore] (storage only) deliberately left open. A macro is looked up
 * fresh from [store] on every firing rather than captured once at schedule
 * time, so editing or deleting a saved macro takes effect on its next
 * scheduled run without having to reschedule.
 *
 * **Concurrency note:** each firing runs [executor] on [scheduler]'s own
 * background thread. [ToolExecutor]'s [AgentStateMachine] is a single
 * mutable `state` field with no locking of its own — safe for one task at a
 * time, which is what [DroidCommandSession] enforces for Pilot/Forge, but
 * not safe if a scheduled firing here and a live Pilot/Forge task drive the
 * *same* [ToolExecutor] concurrently. Give a [MacroScheduler] a
 * [ToolExecutor] (and therefore [MacroExecutor]) bound to its own
 * [AgentStateMachine], separate from any session actively used for
 * Pilot/Forge, until/unless that state machine is made concurrency-safe.
 */
class MacroScheduler(
    private val store: MacroStore,
    private val executor: MacroExecutor,
    private val scheduler: Scheduler,
    private val logger: Logger = NoOpLogger,
) {
    private val active = mutableMapOf<String, Cancellable>()
    private val lock = Any()

    /**
     * Schedules [macroName] to run every [periodMs], first after
     * [initialDelayMs] (defaults to [periodMs] — wait one full period
     * before the first run). Returns `false` without scheduling anything
     * if [macroName] is already scheduled; call [cancel] first to
     * reschedule it with different timing.
     */
    fun scheduleRecurring(
        macroName: String,
        periodMs: Long,
        initialDelayMs: Long = periodMs,
        mode: AgentMode? = null,
        initiator: Initiator? = null,
    ): Boolean {
        synchronized(lock) {
            if (macroName in active) return false
            active[macroName] = scheduler.scheduleAtFixedRate(initialDelayMs, periodMs) {
                runOnce(macroName, mode, initiator)
            }
        }
        logger.info("macro_scheduled", mapOf("macro" to macroName, "periodMs" to periodMs.toString()))
        return true
    }

    /** Stops [macroName]'s recurring schedule. Returns `false` if it wasn't scheduled. */
    fun cancel(macroName: String): Boolean {
        val cancellable = synchronized(lock) { active.remove(macroName) } ?: return false
        cancellable.cancel()
        logger.info("macro_unscheduled", mapOf("macro" to macroName))
        return true
    }

    fun isScheduled(macroName: String): Boolean = synchronized(lock) { macroName in active }

    /**
     * Runs on [scheduler]'s background thread. Never lets an exception
     * escape: an uncaught throw here would make the underlying
     * `ScheduledExecutorService` silently suppress every future firing of
     * this schedule (see [ScheduledExecutorServiceScheduler]'s doc comment).
     */
    private fun runOnce(macroName: String, mode: AgentMode?, initiator: Initiator?) {
        val macro = try {
            store.load(macroName)
        } catch (t: Throwable) {
            logger.error("scheduled_macro_load_failed", mapOf("macro" to macroName), cause = t)
            return
        }
        if (macro == null) {
            logger.warn("scheduled_macro_missing", mapOf("macro" to macroName))
            return
        }

        val outcome = try {
            executor.run(macro, mode = mode, initiator = initiator)
        } catch (t: Throwable) {
            logger.error("scheduled_macro_threw", mapOf("macro" to macroName), cause = t)
            return
        }
        logger.info("scheduled_macro_ran", mapOf("macro" to macroName, "outcome" to outcome::class.simpleName.orEmpty()))
    }
}
