package ai.droidcommand.config

import ai.droidcommand.security.AuditEvent
import ai.droidcommand.security.AuditEventType
import ai.droidcommand.security.AuditLog

/**
 * CAP-013, P1.5. Distinct from [ConfigSource]: a [ConfigSource] answers "what
 * value is configured for this key" and is read fresh on every call (see
 * [EnvConfigSource]'s own doc comment on never capturing a value as a field);
 * a [SecretsVault] answers "what is the current value behind this *secret
 * id*", additionally scoped so a caller can enumerate only the secrets a
 * given capability owns without ever seeing another capability's secret
 * value in the process.
 *
 * `capabilityId` is plain [String] here rather than the roadmap prompt's own
 * `CapabilityId` type: the Capability Registry (CAP-008/CAP-009, P1.0/P1.1)
 * that type belongs to is still MISSING in this repository (per
 * `docs/AUDIT_2026-09-05.md`'s CAP-### reconciliation), so this slice takes
 * the same "thinner than specified" approach already documented there for
 * `RiskTier`'s missing companion types, rather than inventing a whole
 * registry type this module has no other user for.
 *
 * The roadmap prompt's own `putSecret(secretId, value)` signature has no
 * `capabilityId` parameter, yet `listSecretIds(capabilityId)` requires one —
 * the association has to be recorded somewhere, and put time is the only
 * point a [SecretsVault] ever observes a capability id at all. [putSecret]
 * therefore takes an optional `capabilityId` (default `null`, meaning
 * unscoped — excluded from every [listSecretIds] result) as the minimal
 * addition needed to make [listSecretIds] meaningful, rather than silently
 * ignoring the parameter the spec's own two methods can't otherwise agree on.
 */
interface SecretsVault {
    fun getSecret(secretId: String): String?

    fun putSecret(secretId: String, value: String, capabilityId: String? = null)

    fun revokeSecret(secretId: String)

    fun listSecretIds(capabilityId: String): List<String>
}

/**
 * Extends [ConfigSource] with a [SecretsVault] — the roadmap prompt's own
 * required shape for "the existing `ConfigSource`" to grow into.
 */
interface EnhancedConfigSource : ConfigSource {
    fun getSecretsVault(): SecretsVault
}

/**
 * In-memory [SecretsVault]. Values are held only as plain in-memory state —
 * never written to [auditLog] or anywhere else; every audit record names the
 * secret *id*, never its value, matching the roadmap prompt's own example
 * ("capability:termux.package_manager accessed secret:termux-api-token —
 * success"). [auditLog] is optional and additive like every other optional
 * collaborator in this package ([ai.droidcommand.security.SecureToolExecutor]'s
 * own `auditLog`/`grantStore` included) — omitting it changes nothing about
 * whether a call succeeds, only whether it is recorded.
 */
class InMemorySecretsVault(private val auditLog: AuditLog? = null) : SecretsVault {
    private data class Entry(val value: String, val capabilityId: String?)

    private val secrets = mutableMapOf<String, Entry>()

    @Synchronized
    override fun getSecret(secretId: String): String? {
        val entry = secrets[secretId]
        val owner = entry?.capabilityId ?: "unscoped"
        val outcome = if (entry != null) "success" else "not found"
        auditLog?.record(
            AuditEvent(
                AuditEventType.SECRET_ACCESSED,
                "secret:$secretId",
                "capability:$owner accessed secret:$secretId — $outcome",
            ),
        )
        return entry?.value
    }

    @Synchronized
    override fun putSecret(secretId: String, value: String, capabilityId: String?) {
        secrets[secretId] = Entry(value, capabilityId)
    }

    @Synchronized
    override fun revokeSecret(secretId: String) {
        if (secrets.remove(secretId) != null) {
            auditLog?.record(AuditEvent(AuditEventType.SECRET_REVOKED, "secret:$secretId", "revoked secret:$secretId"))
        }
    }

    @Synchronized
    override fun listSecretIds(capabilityId: String): List<String> =
        secrets.filterValues { it.capabilityId == capabilityId }.keys.toList()
}

/**
 * Composes an existing [ConfigSource] (ordinary, non-secret configuration)
 * with a [SecretsVault], without changing how any existing [ConfigSource]
 * caller reads a plain key.
 */
class VaultBackedConfigSource(
    private val delegate: ConfigSource,
    private val vault: SecretsVault,
) : EnhancedConfigSource {
    override fun get(key: String): String? = delegate.get(key)

    override fun getSecretsVault(): SecretsVault = vault
}
