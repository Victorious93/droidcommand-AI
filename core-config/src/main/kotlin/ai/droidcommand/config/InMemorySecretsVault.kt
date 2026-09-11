package ai.droidcommand.config

import ai.droidcommand.agent.CapabilityId
import ai.droidcommand.security.AuditEvent
import ai.droidcommand.security.AuditEventType
import ai.droidcommand.security.AuditLog

/**
 * The real, in-memory [SecretsVault]. **Deliberately not persistent** —
 * this codebase has zero encryption/KeyStore infrastructure anywhere, and
 * a `JsonFileSecretsVault` writing API tokens to plaintext disk without
 * at-rest encryption would be a genuine vulnerability, not merely an
 * honest simplification like this codebase's other in-memory-first
 * stores (`InMemoryGrantStore`/`InMemoryAuditLog`). Persistence is real,
 * deliberately deferred future work, pending real encryption-at-rest
 * infrastructure this codebase doesn't have — not an oversight.
 *
 * `secretId` is treated as capability-scoped by convention:
 * `"<capabilityId>:<name>"` (see [SecretsVault]'s own doc for why). This
 * same split drives both [listSecretIds]'s prefix filter and
 * [getSecret]'s audit line, which reconstructs the spec's own example
 * format ("capability:termux.package_manager accessed
 * secret:termux-api-token — success") directly from it. A `secretId`
 * stored without a `:` simply never appears under any capability's
 * listing — a safe, documented default, not a crash.
 *
 * [putSecret]/[revokeSecret] are **not audited** in this slice — the
 * spec's own "Audit logging" section gives exactly one behavioral
 * example, for [getSecret] (a real "use"); extending audit coverage to
 * writes would be inventing a requirement the spec doesn't state.
 */
class InMemorySecretsVault(private val auditLog: AuditLog) : SecretsVault {
    private val secrets = mutableMapOf<String, String>()
    private val lock = Any()

    /**
     * Always logs the attempt — including a miss — via [auditLog],
     * literally implementing "log secret *use*, not values": only
     * [secretId] and a success/failure word ever reach the audit event,
     * never [value]. **Fails closed on the audit log itself**: if
     * [AuditLog.record] returns `false` (e.g. at capacity), this returns
     * `null` regardless of whether the secret actually exists — the same
     * "an action this system can't audit doesn't happen" restraint
     * `core-security.SecureToolExecutor` already established for tool
     * invocation, applied here to secret access.
     */
    override fun getSecret(secretId: String): String? {
        val value = synchronized(lock) { secrets[secretId] }
        val capabilityPart = secretId.substringBefore(':', missingDelimiterValue = "unknown")
        val namePart = secretId.substringAfter(':', missingDelimiterValue = secretId)
        val outcome = if (value != null) "success" else "not found"
        val recorded = auditLog.record(
            AuditEvent(AuditEventType.SECRET_ACCESSED, secretId, "capability:$capabilityPart accessed secret:$namePart — $outcome"),
        )
        return if (recorded) value else null
    }

    override fun putSecret(secretId: String, value: String) {
        synchronized(lock) { secrets[secretId] = value }
    }

    override fun revokeSecret(secretId: String) {
        synchronized(lock) { secrets.remove(secretId) }
    }

    override fun listSecretIds(capabilityId: CapabilityId): List<String> = synchronized(lock) {
        secrets.keys.filter { it.substringBefore(':', missingDelimiterValue = "") == capabilityId.value }.sorted()
    }
}
