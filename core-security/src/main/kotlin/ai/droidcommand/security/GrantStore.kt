package ai.droidcommand.security

import java.time.Instant

/**
 * An authorization grant for one named capability (e.g. `"root"`,
 * `"remote_shell"`) that a tool may additionally require via
 * [ai.droidcommand.agent.ToolSpec.grantCapability] — layered on top of, not
 * instead of, the ordinary [SecurityPolicy] check. A [singleUse] grant is
 * consumed only once the gated invocation actually *succeeds*, never merely
 * once every gate is passed, so a failed attempt never silently burns a
 * one-time grant.
 */
data class Grant(
    val id: String,
    val capability: String,
    val issuedAt: Instant = Instant.now(),
    val expiresAt: Instant? = null,
    val singleUse: Boolean = true,
)

sealed class GrantCheck {
    data object Live : GrantCheck()
    data class Denied(val reason: String) : GrantCheck()
}

interface GrantStore {
    /** Returns false (rather than evicting an older grant) once at capacity. */
    fun issue(grant: Grant): Boolean

    fun check(id: String?, capability: String, now: Instant = Instant.now()): GrantCheck

    /** Marks a single-use grant spent. A no-op for an unknown id. */
    fun consume(id: String)

    /** Immediately invalidates a grant regardless of expiry/single-use state. */
    fun revoke(id: String)
}

/**
 * In-memory grant lifecycle store. Fails closed at [capacity] (refuses new
 * grants rather than evicting an older one, which would silently reopen an
 * authorization window someone believed was closed).
 */
class InMemoryGrantStore(private val capacity: Int = 10_000) : GrantStore {
    private val grants = mutableMapOf<String, Grant>()
    private val consumed = mutableSetOf<String>()
    private val revoked = mutableSetOf<String>()

    @Synchronized
    override fun issue(grant: Grant): Boolean {
        if (grants.size >= capacity) return false
        grants[grant.id] = grant
        return true
    }

    @Synchronized
    override fun check(id: String?, capability: String, now: Instant): GrantCheck {
        if (id == null) return GrantCheck.Denied("no grant id supplied for capability '$capability'")
        val grant = grants[id] ?: return GrantCheck.Denied("no such grant '$id'")
        if (grant.capability != capability) {
            return GrantCheck.Denied("grant '$id' is for capability '${grant.capability}', not '$capability'")
        }
        if (id in revoked) return GrantCheck.Denied("grant '$id' has been revoked")
        if (grant.singleUse && id in consumed) {
            return GrantCheck.Denied("grant '$id' is single-use and has already been consumed")
        }
        if (grant.expiresAt != null && now.isAfter(grant.expiresAt)) {
            return GrantCheck.Denied("grant '$id' expired at ${grant.expiresAt}")
        }
        return GrantCheck.Live
    }

    @Synchronized
    override fun consume(id: String) {
        consumed += id
    }

    @Synchronized
    override fun revoke(id: String) {
        revoked += id
    }
}
