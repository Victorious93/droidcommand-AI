package ai.droidcommand.security

private class ApprovalOutcome {
    @Volatile var result: Boolean? = null

    @Volatile var error: Throwable? = null
}

/**
 * Adapts the existing boolean-returning [ApprovalPrompt] to [ApprovalProvider],
 * enforcing [ApprovalRequest.timeoutMs] — the concrete gap
 * `docs/AUDIT_2026-09-05.md`'s CAP-014 row names: "a hung `ApprovalPrompt`
 * implementation today has no timeout at all." [ApprovalPrompt] itself is
 * left unchanged, so every existing [SecureToolExecutor] caller and test
 * keeps working exactly as before; this is an additional, opt-in bridge for
 * a caller that wants [ApprovalRequest]/[ApprovalResponse]'s richer shape.
 *
 * The wait is enforced the same way [ai.droidcommand.shell.ProcessBuilderShellExecutor]
 * already bounds a blocking external call in this codebase: run it on a
 * daemon thread and join with a timeout, rather than pulling in an
 * [java.util.concurrent.ExecutorService] this module has no other use for.
 *
 * [ApprovalPrompt.requestApproval] has no cancellation hook of its own — a
 * synchronous [Boolean]-returning call, not a coroutine or a cancellable
 * future — so a genuinely hung prompt implementation keeps running on that
 * background thread after this method returns [ApprovalResponse.TimedOut];
 * this makes the *caller* see a bounded wait and a default-deny outcome, it
 * does not stop the hung call itself. That is a real improvement over
 * today's unbounded block, not a full fix — closing it for good needs
 * [ApprovalPrompt] itself to grow a cancellation contract, out of scope for
 * this slice.
 *
 * A non-positive [ApprovalRequest.timeoutMs] (e.g. [RiskApprovalPolicy]'s own
 * `READ_ONLY` default of `0`) never waits at all rather than being passed to
 * [Thread.join], where `0` means "wait forever" — the opposite of what a
 * zero timeout should mean here.
 *
 * [auditLog] is optional and additive, matching every other optional
 * collaborator in this package; when configured, every outcome is recorded
 * (naming [ApprovalRequest.requestId]/[ApprovalRequest.toolId], never the
 * approval mechanism's own detail) including [ApprovalResponse.TimedOut] and
 * [ApprovalResponse.Unavailable] — the two outcomes a plain `Boolean`-based
 * audit trail could never previously distinguish.
 */
class TimeoutApprovalProvider(
    private val prompt: ApprovalPrompt,
    private val auditLog: AuditLog? = null,
) : ApprovalProvider {
    override fun requestApproval(request: ApprovalRequest): ApprovalResponse {
        val outcome = ApprovalOutcome()
        val thread = Thread {
            try {
                outcome.result = prompt.requestApproval(request.operationDescription)
            } catch (e: Throwable) {
                outcome.error = e
            }
        }.apply {
            isDaemon = true
            start()
        }

        if (request.timeoutMs > 0) {
            thread.join(request.timeoutMs)
        }

        val response = when {
            outcome.error != null -> ApprovalResponse.Unavailable
            outcome.result == true -> ApprovalResponse.Approved
            outcome.result == false -> ApprovalResponse.Denied
            else -> ApprovalResponse.TimedOut
        }

        val eventType = when (response) {
            ApprovalResponse.Approved -> AuditEventType.APPROVAL_APPROVED
            ApprovalResponse.Denied -> AuditEventType.APPROVAL_DENIED
            ApprovalResponse.TimedOut -> AuditEventType.APPROVAL_TIMED_OUT
            ApprovalResponse.Unavailable -> AuditEventType.APPROVAL_UNAVAILABLE
        }
        auditLog?.record(
            AuditEvent(
                eventType,
                "tool:${request.toolId}",
                "approval request '${request.requestId}' (risk=${request.riskTier}) resolved to ${response::class.simpleName}",
            ),
        )

        return response
    }
}
