package ai.droidcommand.activity

import ai.droidcommand.security.ApprovalPrompt

/**
 * Wraps the app's one approval dialog so the feed can say "this bot is waiting for you". It never
 * decides anything: the answer is [delegate]'s, returned unchanged, and an exception from
 * [delegate] propagates after being recorded as a denial.
 *
 * The bot a prompt belongs to comes from [ActivityScope], which [ActivityRecordingToolRunner] sets
 * around each tool call. A prompt raised outside any recorded run (for example a plain Pilot call)
 * is passed through without an entry, rather than attributed to a guess.
 */
class ActivityApprovalPrompt(
    private val delegate: ApprovalPrompt,
    private val log: ActivityLog,
    private val clock: () -> Long = System::currentTimeMillis,
) : ApprovalPrompt {
    override fun requestApproval(reason: String): Boolean =
        recorded("approval", reason) { delegate.requestApproval(reason) }

    override fun requestApproval(toolName: String, input: Map<String, String>, reason: String): Boolean =
        recorded(toolName, reason) { delegate.requestApproval(toolName, input, reason) }

    private fun recorded(what: String, reason: String, ask: () -> Boolean): Boolean {
        val scope = ActivityScope.current() ?: return ask()
        emit(scope, ActivityKind.NEEDS_APPROVAL, "Waiting for you: $what", ActivityRedactor.text(reason, 200))
        val granted = try {
            ask()
        } catch (t: Throwable) {
            emit(scope, ActivityKind.APPROVAL_DENIED, "No answer: $what", ActivityRedactor.text("${t.javaClass.simpleName}: ${t.message.orEmpty()}", 200))
            throw t
        }
        emit(
            scope,
            if (granted) ActivityKind.APPROVAL_GRANTED else ActivityKind.APPROVAL_DENIED,
            if (granted) "You allowed: $what" else "You declined: $what",
            null,
        )
        return granted
    }

    private fun emit(scope: ActivityScope.Current, kind: ActivityKind, summary: String, detail: String?) {
        runCatching { log.append(ActivityEvent(scope.actor, scope.runId, kind, summary, detail, clock())) }
    }
}
