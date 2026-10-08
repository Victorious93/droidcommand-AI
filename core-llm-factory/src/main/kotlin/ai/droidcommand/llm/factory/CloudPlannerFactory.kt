package ai.droidcommand.llm.factory

import ai.droidcommand.agent.Planner
import ai.droidcommand.config.ConfiguredLlmProvider
import ai.droidcommand.config.SecretsVault
import ai.droidcommand.llm.AiProviderInfo
import ai.droidcommand.llm.LlmConfig
import ai.droidcommand.llm.LlmPlanner
import ai.droidcommand.llm.LlmProvider
import ai.droidcommand.llm.ProviderType
import ai.droidcommand.remote.HttpTransport

/** Outcome of [CloudPlannerFactory.create]. [MissingKey.message] never contains a key. */
sealed class PlannerResult {
    data class Ready(val planner: Planner) : PlannerResult()

    data class MissingKey(val message: String) : PlannerResult()
}

/**
 * Builds a real, LLM-backed [Planner] for one BYOK cloud provider and model, reading the API key from
 * [SecretsVault] the same way [ChatSession] does (same [CloudProviderSpec.secretId], so a key saved in
 * Settings is the key used here). The key is resolved lazily per request through `LlmConfig.authToken`,
 * never copied into the planner. The planner this returns is the same [LlmPlanner]
 * [LlmProviderFactory.createPlanner] builds from a `ConfigSource`; this is the app-shaped entry point
 * for callers that have a provider spec and a vault rather than a config source.
 */
object CloudPlannerFactory {
    // Same placeholder ChatSession uses: ContextManager is not driven from here, so the figure only
    // satisfies AiProviderInfo's required field.
    private const val METADATA_ONLY_CONTEXT_TOKENS = 8_000

    fun create(
        vault: SecretsVault,
        spec: CloudProviderSpec,
        model: String,
        transport: HttpTransport,
        providerFor: (CloudProviderSpec, LlmConfig) -> LlmProvider = { s, config ->
            LlmProviderFactory.build(
                ConfiguredLlmProvider(config, AiProviderInfo(s.id, s.label, ProviderType.CLOUD, METADATA_ONLY_CONTEXT_TOKENS)),
                transport,
            ).provider
        },
    ): PlannerResult {
        if (vault.getSecret(spec.secretId).isNullOrBlank()) {
            return PlannerResult.MissingKey("No API key for ${spec.label}. Add one in Settings.")
        }
        val config = LlmConfig(
            provider = spec.id,
            model = model.trim().ifEmpty { spec.defaultModel },
            authToken = { vault.getSecret(spec.secretId) },
        )
        return PlannerResult.Ready(LlmPlanner(providerFor(spec, config)))
    }
}
