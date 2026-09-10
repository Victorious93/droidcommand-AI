package ai.droidcommand.llm

/**
 * Whether an [LlmProvider] runs locally/self-hosted or against a remote
 * cloud API (CAP-003, P0.3: "prefer... Configured Local/Self-Hosted AI"
 * before "Remote AI Provider (Cloud API)").
 *
 * **Caller-declared, never inferred from [LlmConfig.endpoint].** Guessing
 * "local" from a hostname/IP pattern would be unreliable — a self-hosted
 * model can sit behind a LAN IP, a Tailscale address, a Docker hostname,
 * or a reverse proxy, none of which is honestly detectable by
 * string-sniffing — and would misclassify silently. This mirrors
 * `core-agent.Initiator`'s own established precedent: a self-declared
 * policy value, not a cryptographically or mechanically proven one.
 */
enum class Locality { LOCAL, REMOTE }

/** An [LlmProvider] paired with its caller-declared [locality]. */
data class LocalityAwareProvider(val provider: LlmProvider, val locality: Locality)

/**
 * Sorts [providers] with every [Locality.LOCAL] provider before every
 * [Locality.REMOTE] one, preserving relative order within each group
 * (a stable sort) — CAP-003's "prefer local before remote" provider
 * preference, made concrete. The result is a plain `List<LlmProvider>`
 * that plugs directly into [ModelRouter]'s existing, unchanged
 * constructor, whose own doc comment already anticipated exactly this
 * ordering ("e.g. a local model first, then a remote/cloud one") without
 * anything computing it until now.
 */
fun localFirstOrder(providers: List<LocalityAwareProvider>): List<LlmProvider> =
    providers.sortedBy { it.locality.ordinal }.map { it.provider }
