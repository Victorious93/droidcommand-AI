package ai.droidcommand.config

import ai.droidcommand.agent.CapabilityId

/**
 * P1.5's own required abstraction, verbatim: a unified secrets vault
 * providers request secrets from by reference, never seeing plaintext
 * outside the boundary that needs it. All three mutating/reading methods
 * drop the spec's `suspend` — the same stated deviation every `suspend
 * fun` in this codebase has taken since P0.1: there is no coroutines
 * dependency anywhere in this repository.
 *
 * **A real, stated design convention filling a gap the literal signature
 * leaves open:** [listSecretIds] takes a [CapabilityId], but neither
 * [putSecret] nor [getSecret] does — there is no other way for this
 * interface to associate a `secretId` with the capability that owns it.
 * [InMemorySecretsVault] resolves this by treating `secretId` as
 * capability-scoped by convention: `"<capabilityId>:<name>"`, splitting
 * on the first `:` (safe — [CapabilityId]'s own regex forbids `:` inside
 * a capability id). See [InMemorySecretsVault]'s own doc for the full
 * rationale, including how this same convention drives the "Audit
 * logging" section's own example format.
 */
interface SecretsVault {
    fun getSecret(secretId: String): String?

    fun putSecret(secretId: String, value: String)

    fun revokeSecret(secretId: String)

    fun listSecretIds(capabilityId: CapabilityId): List<String>
}

/** Verbatim P1.5 shape — extends the existing, unmodified [ConfigSource]. */
interface EnhancedConfigSource : ConfigSource {
    fun getSecretsVault(): SecretsVault
}
