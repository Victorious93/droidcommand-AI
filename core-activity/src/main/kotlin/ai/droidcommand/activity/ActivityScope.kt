package ai.droidcommand.activity

/**
 * Who a tool call belongs to, for the code that runs *inside* it. The app has one shared approval
 * dialog; when it opens, [ActivityApprovalPrompt] needs to know which bot is waiting for the owner.
 * [ActivityRecordingToolRunner] sets this for the duration of one synchronous tool call on the
 * calling thread, so parallel bots on different threads never see each other's context, and it is
 * always restored in a `finally`.
 */
object ActivityScope {
    data class Current(val actor: ActivityActor, val runId: String)

    private val holder = ThreadLocal<Current?>()

    fun current(): Current? = holder.get()

    fun <T> within(current: Current, block: () -> T): T {
        val previous = holder.get()
        holder.set(current)
        try {
            return block()
        } finally {
            holder.set(previous)
        }
    }
}
