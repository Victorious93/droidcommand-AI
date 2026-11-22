package ai.droidcommand.security

import ai.droidcommand.agent.ROOT_EQUIVALENT_CATEGORIES
import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.ToolRegistry
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * One job the owner is asked to cover in a batch. [argsDigest] is [ArgsDigest.of] the arguments the owner was shown, so a
 * later assignment with different arguments is not covered. [summary] is display text only (untrusted, never decides anything).
 */
data class BatchJob(val jobId: String, val toolName: String, val argsDigest: String, val summary: String = "")

/** A plan's jobs as offered for review. [issuer] names who sent it (the coordinator), for the audit log. */
data class BatchOffer(val planId: String, val issuer: String, val jobs: List<BatchJob>)

data class StillAsk(val job: BatchJob, val reason: String)

/** The result of reviewing an offer. Nothing is approved by reviewing. */
sealed class BatchReview {
    /** [covered] is what one approval would cover; each of [stillAsk] keeps asking for its own tap, with the reason why. */
    data class Reviewable(
        val offer: BatchOffer,
        val covered: List<BatchJob>,
        val stillAsk: List<StillAsk>,
        val ttl: Duration,
    ) : BatchReview()

    /** The offer is fine but no job in it can be covered, so there is nothing to approve. */
    data class NothingToCover(val reason: String) : BatchReview()

    /** The offer cannot be reviewed at all (feature off, too big, malformed). */
    data class Refused(val reason: String) : BatchReview()
}

/** What an approval covers. Held in memory only: a restart ends every batch, and the jobs then ask one by one. */
data class BatchGrant(
    val id: String,
    val planId: String,
    val issuer: String,
    val issuedAt: Instant,
    val expiresAt: Instant,
    val jobs: List<BatchJob>,
)

/** Limits on a batch. Deliberately small; the owner can only choose a shorter time, never a longer one. */
data class BatchApprovalPolicy(
    val maxJobs: Int = 25,
    val defaultTtl: Duration = Duration.ofMinutes(15),
    val maxTtl: Duration = Duration.ofMinutes(60),
    /** Live batches at once. Beyond this a new approval is refused rather than evicting an older one. */
    val maxActive: Int = 8,
    val summaryChars: Int = 120,
)

/** Which jobs may ever be covered by a batch. A reason string means "no, and here is why"; null means "yes". */
fun interface BatchEligibility {
    fun refusal(toolName: String): String?
}

/**
 * The structural floor, checked at review, at approval and again at every use. A tool is coverable only if the app explicitly
 * allows it ([allowedTools]) AND it is none of: root, behind its own grant, root-level permission category, or a shell /
 * root / remote-control / pentest / destructive tool by name (the same tokens [ActionRiskClassifier] refuses to auto-approve,
 * minus the `fleet` and `remote` words that every fleet job's name contains). An unknown tool is never coverable.
 */
class ToolBatchEligibility(
    private val registry: ToolRegistry,
    private val allowedTools: () -> Set<String>,
) : BatchEligibility {
    override fun refusal(toolName: String): String? {
        if (toolName !in allowedTools()) return "this kind of job is not allowed in a batch"
        val spec = runCatching { registry.get(toolName).spec }.getOrNull() ?: return "this phone does not know how to run it"
        val name = spec.name.lowercase()
        return when {
            spec.requiresRoot || spec.securityLevel == SecurityLevel.ROOT -> "it needs root"
            spec.grantCapability != null -> "it needs its own '${spec.grantCapability}' grant"
            spec.permissionCategory?.let { it in ROOT_EQUIVALENT_CATEGORIES } == true -> "it needs a root-level permission"
            NEVER_IN_A_BATCH.any { it in name } -> "it is shell, root, remote-control, pentest or destructive tooling"
            else -> null
        }
    }

    companion object {
        /** [ActionRiskClassifier.NEVER_AUTO_APPROVE_NAME_TOKENS] without the two words every fleet job's name carries. */
        val NEVER_IN_A_BATCH: Set<String> = ActionRiskClassifier.NEVER_AUTO_APPROVE_NAME_TOKENS - setOf("fleet", "remote")
    }
}

/**
 * Lets one approval from the owner cover a reviewed batch of fleet jobs, so a plan with many steps does not mean many taps.
 * It is an **opt-in feature**: [enabled] is the owner's switch, read again on every use, and it is off unless they turn it on.
 *
 * What an approval is, and is not:
 * - It replaces only the owner's *tap* (see [BatchApprovalPrompt]). Policy, mode, initiator, grant and audit checks in
 *   [SecureToolExecutor] all still run, and a job whose tool is not coverable keeps asking.
 * - It is bound to the exact jobs shown: job id, tool and [ArgsDigest] of the arguments. Any difference means not covered.
 * - Each job is covered once. A retry of the same job asks again.
 * - It expires (default 15 min, never over 60), can be revoked at any moment, ends when the switch goes off, and lives only
 *   in memory.
 * - Issuing, using and refusing coverage is written to the audit log. If the audit log cannot record, nothing is covered.
 *
 * Thread-safe: jobs run on their own threads.
 */
class BatchApprovals(
    private val eligibility: BatchEligibility,
    private val enabled: () -> Boolean,
    private val policy: BatchApprovalPolicy = BatchApprovalPolicy(),
    private val auditLog: AuditLog? = null,
    private val clock: () -> Instant = Instant::now,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private class State(val grant: BatchGrant) {
        val used = mutableSetOf<String>()
        var revoked = false
    }

    private val states = LinkedHashMap<String, State>()

    /** Works out what an approval would cover. Pure with respect to approvals: nothing is issued. */
    fun review(offer: BatchOffer): BatchReview {
        if (!enabled()) return BatchReview.Refused("Batch approval is switched off on this phone.")
        if (!ID.matches(offer.planId)) return BatchReview.Refused("The plan has an invalid id.")
        if (offer.jobs.isEmpty()) return BatchReview.Refused("The plan lists no jobs.")
        if (offer.jobs.size > policy.maxJobs) return BatchReview.Refused("The plan has ${offer.jobs.size} jobs; a batch covers at most ${policy.maxJobs}.")
        if (offer.jobs.map { it.jobId }.toSet().size != offer.jobs.size) return BatchReview.Refused("The plan lists the same job twice.")
        if (offer.jobs.any { !ID.matches(it.jobId) || !DIGEST.matches(it.argsDigest) }) return BatchReview.Refused("The plan has a malformed job.")
        val covered = mutableListOf<BatchJob>()
        val ask = mutableListOf<StillAsk>()
        for (job in offer.jobs) {
            val shown = job.copy(summary = job.summary.take(policy.summaryChars))
            val why = eligibility.refusal(job.toolName)
            if (why == null) covered += shown else ask += StillAsk(shown, why)
        }
        if (covered.isEmpty()) return BatchReview.NothingToCover("None of the ${offer.jobs.size} jobs can be covered; each will ask you.")
        return BatchReview.Reviewable(offer, covered, ask, policy.defaultTtl)
    }

    /**
     * The owner said yes to [review]. Issues the batch, or returns null when it cannot: the switch is off, a job stopped being
     * coverable since the review, too many batches are live, or the audit log could not record it. [ttl] can only shorten the
     * time, never go past [BatchApprovalPolicy.maxTtl].
     */
    @Synchronized
    fun approve(review: BatchReview.Reviewable, ttl: Duration = review.ttl): BatchGrant? {
        if (!enabled()) return null
        val now = clock()
        prune(now)
        if (states.values.count { live(it, now) } >= policy.maxActive) return null
        val jobs = review.covered.filter { eligibility.refusal(it.toolName) == null }
        if (jobs.isEmpty()) return null
        val life = if (ttl.isNegative || ttl.isZero) return null else minOf(ttl, policy.maxTtl)
        val grant = BatchGrant(newId(), review.offer.planId, review.offer.issuer, now, now.plus(life), jobs)
        val tools = jobs.groupingBy { it.toolName }.eachCount().entries.joinToString(", ") { "${it.value}× ${it.key}" }
        val detail = "plan '${grant.planId}' from ${grant.issuer}: ${jobs.size} jobs ($tools), valid until ${grant.expiresAt}"
        if (auditLog?.record(AuditEvent(AuditEventType.GRANT_ISSUED, subject(grant.id), detail)) == false) return null
        states[grant.id] = State(grant)
        return grant
    }

    /**
     * Is this job covered right now? If so it is marked used (once) and the use is audited, and the caller skips the tap.
     * Every reason it is not covered is a plain `false`; a job that belongs to a live batch but does not match (different tool
     * or arguments, or already used) is also audited as a denial.
     */
    @Synchronized
    fun tryCover(jobId: String, toolName: String, argsDigest: String): Boolean {
        if (!enabled()) return false
        val now = clock()
        for (state in states.values) {
            // Not just live ones: a replay of the last job of a finished batch is still worth auditing as a denial.
            if (state.revoked || now.isAfter(state.grant.expiresAt)) continue
            val job = state.grant.jobs.firstOrNull { it.jobId == jobId } ?: continue
            val why = when {
                jobId in state.used -> "job '$jobId' was already covered once"
                job.toolName != toolName -> "job '$jobId' was approved as '${job.toolName}', not '$toolName'"
                job.argsDigest != argsDigest -> "job '$jobId' has different arguments from the ones you approved"
                else -> eligibility.refusal(toolName)?.let { "job '$jobId' can no longer be covered: $it" }
            }
            if (why != null) {
                auditLog?.record(AuditEvent(AuditEventType.GRANT_DENIED, subject(state.grant.id), why))
                return false
            }
            val recorded = auditLog?.record(AuditEvent(AuditEventType.GRANT_CONSUMED, subject(state.grant.id), "job '$jobId' ($toolName) covered by your batch approval"))
            if (recorded == false) return false
            state.used += jobId
            return true
        }
        return false
    }

    /** Ends one batch now. True when it was live. */
    @Synchronized
    fun revoke(batchId: String, reason: String): Boolean {
        val state = states[batchId] ?: return false
        if (state.revoked) return false
        state.revoked = true
        auditLog?.record(AuditEvent(AuditEventType.GRANT_REVOKED, subject(batchId), reason))
        return true
    }

    /** Ends every live batch (the switch went off, the coordinator was unpaired or revoked, the owner pressed revoke). */
    @Synchronized
    fun revokeAll(reason: String): Int {
        val now = clock()
        var count = 0
        for ((id, state) in states) {
            if (live(state, now)) {
                state.revoked = true
                auditLog?.record(AuditEvent(AuditEventType.GRANT_REVOKED, subject(id), reason))
                count++
            }
        }
        return count
    }

    /** Live batches, oldest first, for showing the owner what is currently approved. */
    @Synchronized
    fun active(): List<BatchGrant> {
        val now = clock()
        return states.values.filter { live(it, now) }.map { it.grant }
    }

    /** How many of a live batch's jobs are still waiting to be covered; 0 when it is not live. */
    @Synchronized
    fun remaining(batchId: String): Int {
        val state = states[batchId]?.takeIf { live(it, clock()) } ?: return 0
        return state.grant.jobs.size - state.used.size
    }

    private fun live(state: State, now: Instant): Boolean =
        !state.revoked && !now.isAfter(state.grant.expiresAt) && state.used.size < state.grant.jobs.size

    /** Drops batches that can never be used again, so memory does not grow; their audit records stay in the audit log. */
    private fun prune(now: Instant) {
        states.entries.removeIf { !live(it.value, now) && now.isAfter(it.value.grant.expiresAt.plus(PRUNE_AFTER)) }
    }

    private fun subject(batchId: String) = "batch:$batchId"

    private companion object {
        val ID = Regex("[A-Za-z0-9._-]{1,64}")
        val DIGEST = Regex("[0-9a-f]{64}")
        val PRUNE_AFTER: Duration = Duration.ofMinutes(10)
    }
}
