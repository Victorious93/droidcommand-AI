package ai.droidcommand.llm.routing

import ai.droidcommand.agent.Message
import ai.droidcommand.agent.Planner
import ai.droidcommand.agent.Role
import ai.droidcommand.config.ConfiguredLlmProvider
import ai.droidcommand.config.SecretsVault
import ai.droidcommand.llm.AiProviderInfo
import ai.droidcommand.llm.LlmConfig
import ai.droidcommand.llm.LlmPlanner
import ai.droidcommand.llm.LlmProvider
import ai.droidcommand.llm.LlmRequest
import ai.droidcommand.llm.LlmResponse
import ai.droidcommand.llm.ModelRouter
import ai.droidcommand.llm.ProviderCapability
import ai.droidcommand.llm.ProviderType
import ai.droidcommand.llm.factory.ChatResult
import ai.droidcommand.llm.factory.CloudProviderSpec
import ai.droidcommand.llm.factory.LlmProviderFactory
import ai.droidcommand.llm.factory.LocalProviderResources
import ai.droidcommand.llm.local.LocalLlmProvider
import ai.droidcommand.llm.pchost.PcHostChatProvider
import ai.droidcommand.llm.pchost.PcHostTimeouts
import ai.droidcommand.remote.HttpTransport
import ai.droidcommand.remote.RequestCancellation

/** Outcome of [PolicyInference.provider]. */
sealed class RoutedProvider {
    /** [provider] tries [plan]'s candidates in order through a [ModelRouter]. */
    data class Ready(val provider: LlmProvider, val plan: RoutePlan) : RoutedProvider()

    /** Nothing the policy allows can serve the feature. [message] says which places were considered, why, and what to do. */
    data class Refused(val message: String, val plan: RoutePlan) : RoutedProvider()
}

/** Outcome of [PolicyInference.planner]. */
sealed class RoutedPlanner {
    data class Ready(val planner: Planner, val plan: RoutePlan) : RoutedPlanner()

    data class Refused(val message: String) : RoutedPlanner()
}

/**
 * Policy-routed inference for everything that is not a chat turn: Plan and Pilot/Forge (a planner), the code and
 * companion assist pop-ups, automation prompts and bot runs (one prompt, one reply). It gives those features the same
 * places, order and checks Chat gets from [RouteResolver], and hands the ordered providers to the existing
 * [ModelRouter], whose fallback rule then applies unchanged.
 *
 * Only for a routed policy. Under [InferencePolicy.MANUAL] each feature keeps the provider picked for it and this
 * class is not used.
 *
 * A [ModelRouter] request is one model call that has not run any tool yet (a planner decides, then the engine runs
 * the tool), so moving on to the next place repeats no action.
 */
class PolicyInference(
    private val resolver: RouteResolver,
    private val vault: SecretsVault,
    private val transport: HttpTransport,
    private val local: () -> LocalProviderResources?,
    private val pcHostTimeouts: PcHostTimeouts = PcHostTimeouts(),
    private val cloudProviderFor: (CloudProviderSpec, LlmConfig) -> LlmProvider = { spec, config ->
        LlmProviderFactory.build(
            ConfiguredLlmProvider(config, AiProviderInfo(spec.id, spec.label, ProviderType.CLOUD, METADATA_ONLY_CONTEXT_TOKENS)),
            transport,
        ).provider
    },
) {
    fun policy(): InferencePolicy = resolver.policy()

    /** Null when [type] may receive text for [feature] under the policy in force; otherwise the refusal to show. */
    fun refusalFor(type: ProviderType, feature: String): String? = resolver.refusalFor(type, feature)

    /** A provider for [need], or why there is none. [cancellation] closes a PC request in flight. */
    fun provider(need: RouteNeed, cancellation: RequestCancellation = RequestCancellation()): RoutedProvider {
        val plan = resolver.plan(need)
        val allowed = plan.candidates.filter { plan.policy.permits(it.type) }
        if (allowed.isEmpty()) return RoutedProvider.Refused(plan.refusal(need.feature), plan)
        val providers = allowed.map { candidate ->
            when (candidate) {
                is RouteCandidate.Device -> deviceProvider(candidate.modelId)
                is RouteCandidate.Pc -> {
                    val key = vault.getSecret(candidate.target.kind.secretId)?.takeIf { it.isNotBlank() }
                    PcHostChatProvider(candidate.target, key, transport, pcHostTimeouts, cancellation)
                }
                is RouteCandidate.Cloud -> cloudProviderFor(
                    candidate.spec,
                    LlmConfig(provider = candidate.spec.id, model = candidate.model, authToken = { vault.getSecret(candidate.spec.secretId) }),
                )
            }
        }
        return RoutedProvider.Ready(ModelRouter(providers), plan.copy(candidates = allowed))
    }

    /**
     * A planner for Plan (Forge). A planner drives tools on every step, so only a place the resolver marks as
     * tool-calling qualifies: a PC model whose server reports tool support, or the cloud provider. An on-device model
     * is not used for planning (a helper bot's tools in Chat can run on one; planning on one is untested).
     */
    fun planner(feature: String): RoutedPlanner =
        when (val routed = provider(RouteNeed(feature, required = setOf(ProviderCapability.TOOL_CALLING)))) {
            is RoutedProvider.Ready -> RoutedPlanner.Ready(LlmPlanner(routed.provider), routed.plan)
            is RoutedProvider.Refused -> RoutedPlanner.Refused(routed.message)
        }

    /**
     * One prompt, one reply, nothing stored. The reply's [ChatResult.Reply.route] names the first place the policy
     * would use; which place of a fallback chain actually answered is not reported by [ModelRouter], so when more than
     * one place was eligible the notice says so instead of naming one.
     */
    fun ask(feature: String, prompt: String): ChatResult {
        if (prompt.isBlank()) return ChatResult.Failure("Prompt is empty.")
        val text = prompt.trim()
        val budget = RequestBudget.of(null, emptyList(), text, text, emptyList(), emptyList(), OUTPUT_RESERVE_TOKENS)
        val routed = provider(RouteNeed(feature, minimumWindowTokens = budget.minimumWindowTokens()))
        val ready = when (routed) {
            is RoutedProvider.Refused -> return ChatResult.Failure(routed.message, RouteReport(routed.plan.policy, null, routed.plan.skipped, budget))
            is RoutedProvider.Ready -> routed
        }
        val keys = ready.plan.candidates.mapNotNull { candidate ->
            when (candidate) {
                is RouteCandidate.Device -> null
                is RouteCandidate.Pc -> vault.getSecret(candidate.target.kind.secretId)
                is RouteCandidate.Cloud -> vault.getSecret(candidate.spec.secretId)
            }
        }
        fun scrub(message: String) = keys.filter { it.length >= MIN_REDACTABLE_KEY_LENGTH }.fold(message) { m, key -> m.replace(key, "[redacted]") }
        val response = try {
            ready.provider.complete(LlmRequest(systemPrompt = null, messages = listOf(Message(Role.USER, text))))
        } catch (e: Exception) {
            return ChatResult.Failure(scrub("Request failed: ${e.message ?: e.javaClass.simpleName}"))
        }
        val places = ready.plan.candidates.map { it.endpoint }
        return when (response) {
            is LlmResponse.Text ->
                if (response.content.isBlank()) {
                    ChatResult.Failure("The model returned an empty reply.")
                } else {
                    ChatResult.Reply(
                        response.content,
                        notice = if (places.size == 1) {
                            "Answered by ${places.single().describe()}."
                        } else {
                            "Answered by the first of these that could: ${places.joinToString("; ") { it.describe() }}."
                        },
                        route = RouteReport(ready.plan.policy, places.singleOrNull(), ready.plan.skipped, budget),
                    )
                }
            is LlmResponse.ToolCall -> ChatResult.Failure("The model asked to call a tool, which this request does not support.")
            is LlmResponse.Error -> ChatResult.Failure(scrub(response.error.message), RouteReport(ready.plan.policy, null, ready.plan.skipped, budget))
        }
    }

    private fun deviceProvider(modelId: String): LlmProvider {
        val resources = local() ?: throw IllegalStateException("On-device models are not available on this device.")
        return resources.pool?.provider(modelId)
            ?: LocalLlmProvider(modelId, resources.repository, resources.backendFactory(), memory = resources.memory)
    }

    private companion object {
        const val METADATA_ONLY_CONTEXT_TOKENS = 8_000
        const val MIN_REDACTABLE_KEY_LENGTH = 6
        const val OUTPUT_RESERVE_TOKENS = 512
    }
}
