package ai.droidcommand.llm.routing

import ai.droidcommand.agent.Task
import ai.droidcommand.config.SecretsVault
import ai.droidcommand.llm.AiProviderInfo
import ai.droidcommand.llm.DefaultAiProviderSelector
import ai.droidcommand.llm.LlmConfig
import ai.droidcommand.llm.LlmError
import ai.droidcommand.llm.LlmProvider
import ai.droidcommand.llm.LlmRequest
import ai.droidcommand.llm.LlmResponse
import ai.droidcommand.llm.ProviderCapability
import ai.droidcommand.llm.ProviderPreferences
import ai.droidcommand.llm.ProviderType
import ai.droidcommand.llm.RegisteredProvider
import ai.droidcommand.llm.factory.CloudProviderSpec
import ai.droidcommand.llm.local.ModelRepository
import ai.droidcommand.llm.pchost.PcHostKind
import ai.droidcommand.llm.pchost.PcHostSettingsStore
import ai.droidcommand.llm.pchost.PcHostTarget
import ai.droidcommand.llm.pchost.PcModelFacts
import ai.droidcommand.llm.pchost.PcModelLocation

/** Where a reply came from, or would have: shown to the owner next to every routed reply and refusal. */
data class RouteEndpoint(val kind: Kind, val where: String, val model: String) {
    enum class Kind(val label: String) { DEVICE("This device"), PC("My PC"), CLOUD("Cloud") }

    /** For example `My PC (Ollama at http://192.168.1.20:11434), model gemma3:4b`. */
    fun describe(): String = "$where, model $model"
}

/** One place a routed request was, or was not, sent. [reason] is null only for the place that answered. */
data class RouteAttempt(val endpoint: RouteEndpoint, val outcome: Outcome, val reason: String?) {
    enum class Outcome {
        /** This place wrote the reply. */
        ANSWERED,

        /** Nothing was sent here: not allowed by the policy, not set up, not reachable, not capable, or too small. */
        SKIPPED,

        /** A request was sent here and failed. */
        FAILED,
    }
}

/**
 * What the owner is shown about a routed request: the [policy] in force, where the reply came from ([chosen], null
 * when nothing answered), every other place considered and why it was not used ([attempts]), the estimated size of
 * the request ([budget]) and the window it was fitted to, and how many older messages were left out to fit.
 */
data class RouteReport(
    val policy: InferencePolicy,
    val chosen: RouteEndpoint?,
    val attempts: List<RouteAttempt>,
    val budget: RequestBudget? = null,
    val windowTokens: Int? = null,
    val droppedMessages: Int = 0,
) {
    /** Why the reply did not come from the first place the policy names. Null when it did, or when nothing was skipped. */
    val fallbackReason: String?
        get() = attempts.filter { it.outcome != RouteAttempt.Outcome.ANSWERED }
            .takeIf { it.isNotEmpty() }
            ?.joinToString(" ") { "${it.endpoint.where} was not used: ${it.reason.orEmpty().trimEnd('.')}." }

    /** The line shown under a reply. */
    fun summary(): String {
        val head = chosen?.let { "Answered by ${it.describe()}." } ?: "Nothing answered."
        val window = when {
            budget == null -> null
            windowTokens == null -> "Request ${budget.summary()}; this endpoint does not report its context window."
            else -> "Request ${budget.summary()} for a window of $windowTokens tokens."
        }
        val dropped = droppedMessages.takeIf { it > 0 }?.let { "$it older message${if (it == 1) " was" else "s were"} left out of this request to fit that window." }
        return listOfNotNull(head, "Policy: ${policy.label}.", fallbackReason, window, dropped).joinToString(" ")
    }
}

/** One place a request could go. [unavailable] is why nothing can be sent there right now, or null. */
sealed class RouteCandidate {
    abstract val endpoint: RouteEndpoint

    /** The recipient class the policy is checked against. */
    abstract val type: ProviderType
    abstract val contextTokens: Int?
    abstract val toolCalling: Boolean
    abstract val unavailable: String?

    /** Stable within one plan. */
    val id: String get() = "${endpoint.kind}:${endpoint.where}:${endpoint.model}"

    data class Device(val modelId: String, val label: String, override val contextTokens: Int?, override val unavailable: String?) : RouteCandidate() {
        override val endpoint = RouteEndpoint(RouteEndpoint.Kind.DEVICE, "This device", label)
        override val type = ProviderType.LOCAL
        override val toolCalling = false
    }

    data class Pc(val target: PcHostTarget, val facts: PcModelFacts, override val type: ProviderType, override val unavailable: String?) : RouteCandidate() {
        override val endpoint = RouteEndpoint(RouteEndpoint.Kind.PC, "My PC (${target.kind.label} at ${target.address.base})", target.model)
        override val contextTokens: Int? get() = facts.contextTokens
        override val toolCalling: Boolean get() = facts.toolCalling == true
    }

    data class Cloud(val spec: CloudProviderSpec, val model: String, override val unavailable: String?) : RouteCandidate() {
        override val endpoint = RouteEndpoint(RouteEndpoint.Kind.CLOUD, "Cloud (${spec.label})", model)
        override val type = ProviderType.CLOUD
        override val contextTokens: Int? = null
        override val toolCalling = true
    }
}

/** What a feature needs from the place that serves it. */
data class RouteNeed(
    /** For messages: "Chat", "Plan", "Remember this chat". */
    val feature: String,
    /** A candidate without these is skipped. Tools in Chat are optional, so Chat does not list them here. */
    val required: Set<ProviderCapability> = emptySet(),
    /** Smallest window that can hold the request ([RequestBudget.minimumWindowTokens]), when known. */
    val minimumWindowTokens: Int? = null,
)

/** [candidates] in the order to try them; [skipped] with the reason each was left out. */
data class RoutePlan(val policy: InferencePolicy, val candidates: List<RouteCandidate>, val skipped: List<RouteAttempt>) {
    /** What to tell the owner when nothing can serve the request, with what to do about it. */
    fun refusal(feature: String): String {
        val detail = skipped.joinToString(" ") { "${it.endpoint.where}: ${it.reason.orEmpty().trimEnd('.')}." }
        return "$feature was not sent anywhere. Policy \"${policy.label}\" allows ${allowedPlaces(policy)}, and none can serve this request. " +
            "$detail Fix one of these, or choose another policy in Chat, Options, Where replies come from."
    }

    private fun allowedPlaces(policy: InferencePolicy): String = policy.order.joinToString(", then ") {
        when (it) {
            ProviderType.LOCAL -> "this device"
            ProviderType.SELF_HOSTED -> "a model that runs on your PC"
            ProviderType.CLOUD -> "the cloud provider"
        }
    }
}

/** The cloud provider and model a policy with cloud fallback may use: the ones picked in Chat. */
data class CloudChoice(val spec: CloudProviderSpec, val model: String)

/**
 * Turns the owner's [InferencePolicy] and saved choices into the ordered list of places one request may go, through
 * the existing [DefaultAiProviderSelector]: each place becomes an [AiProviderInfo] (type, availability, capabilities,
 * context window) and the selector's own filter and ranking decide, so the reasons shown to the owner are the
 * selector's decisions.
 *
 * Nothing is contacted for a place the policy does not allow: under "This device only" no request of any kind leaves
 * the device. A PC that is allowed is asked about its model once per plan ([inspectPc]), which is also the
 * reachability check.
 *
 * A PC model counts as running on the PC only when the server establishes it ([PcModelLocation.SERVER_LOCAL]). A
 * cloud-backed model, or one whose location the server does not report, is treated as a cloud recipient: refused
 * under the three local policies, and under cloud fallback tried last, after this device.
 */
class RouteResolver(
    private val vault: SecretsVault,
    private val settings: InferenceSettingsStore,
    private val pcHosts: PcHostSettingsStore,
    /** The repository on-device models are registered in, or null when on-device inference is not available. */
    private val deviceModels: () -> ModelRepository?,
    private val cloudChoice: () -> CloudChoice?,
    private val inspectPc: (PcHostTarget, String?) -> PcModelFacts,
    /** Chat's own picks, used while [InferenceSettings.deviceModelId] / [InferenceSettings.pcKind] are not set. */
    private val fallbackDeviceModelId: () -> String? = { null },
    private val fallbackPcKind: () -> PcHostKind? = { null },
) {
    fun settings(): InferenceSettings = runCatching { settings.load() }.getOrElse { InferenceSettings(policy = InferencePolicy.DEVICE_ONLY) }

    /** The policy in force (cloud fallback counts only once confirmed). */
    fun policy(): InferencePolicy = settings().effective

    /**
     * Null when [type] may receive text for [feature]; otherwise the refusal to show. Call this before any request
     * that is not routed through [plan] (a web search, a cloud call a screen makes on its own).
     */
    fun refusalFor(type: ProviderType, feature: String): String? {
        val policy = policy()
        if (policy.permits(type)) return null
        val what = when (type) {
            ProviderType.CLOUD -> "uses an internet service"
            ProviderType.SELF_HOSTED -> "uses your PC"
            ProviderType.LOCAL -> "uses the on-device model"
        }
        return "$feature $what, which the policy \"${policy.label}\" does not allow. Nothing was sent. " +
            "Change it in Chat, Options, Where replies come from."
    }

    fun plan(need: RouteNeed): RoutePlan {
        val saved = settings()
        val policy = saved.effective
        val skipped = mutableListOf<RouteAttempt>()
        val candidates = mutableListOf<RouteCandidate>()

        if (ProviderType.LOCAL in policy.order) candidates += device(saved)
        if (ProviderType.SELF_HOSTED in policy.order) pc(saved, policy)?.let { candidates += it }
        if (ProviderType.CLOUD in policy.order) candidates += cloud()

        val registered = candidates.map { candidate ->
            RegisteredProvider(
                AiProviderInfo(
                    id = candidate.id,
                    name = candidate.endpoint.describe(),
                    type = candidate.type,
                    // An endpoint that does not report its window is not filtered on it; the request is sent whole.
                    maxContextTokens = candidate.contextTokens ?: Int.MAX_VALUE,
                    available = candidate.unavailable == null,
                    capabilities = buildSet {
                        add(ProviderCapability.STREAMING)
                        if (candidate.toolCalling) add(ProviderCapability.TOOL_CALLING)
                    },
                ),
                NotDispatched,
            )
        }
        val decisions = DefaultAiProviderSelector(registered).explain(
            Task(id = "route", description = need.feature),
            ProviderPreferences(
                requiredCapabilities = need.required,
                minContextTokens = need.minimumWindowTokens,
                allowedTypes = policy.order.toSet(),
                typeOrder = policy.order,
            ),
        )
        val byId = candidates.associateBy { it.id }
        val ordered = mutableListOf<RouteCandidate>()
        for (decision in decisions) {
            val candidate = byId.getValue(decision.registered.info.id)
            if (decision.eligible) {
                ordered += candidate
            } else {
                skipped += RouteAttempt(candidate.endpoint, RouteAttempt.Outcome.SKIPPED, candidate.unavailable ?: explain(decision.excludedBecause, need, candidate))
            }
        }
        return RoutePlan(policy, ordered, skipped)
    }

    private fun explain(selectorReason: String?, need: RouteNeed, candidate: RouteCandidate): String = when {
        ProviderCapability.TOOL_CALLING in need.required && !candidate.toolCalling -> when (candidate) {
            is RouteCandidate.Device -> "${need.feature} needs a model that can call tools, and on-device models cannot"
            is RouteCandidate.Pc -> "${need.feature} needs a model that can call tools, and ${candidate.facts.toolCallingDetail.ifBlank { "this model is not reported to" }}"
            is RouteCandidate.Cloud -> selectorReason.orEmpty()
        }
        else -> selectorReason.orEmpty()
    }

    private fun device(saved: InferenceSettings): RouteCandidate.Device {
        val id = saved.deviceModelId ?: fallbackDeviceModelId()
        val repository = deviceModels()
        val meta = id?.let { repository?.get(it) }
        val problem = when {
            repository == null -> "on-device models are not available on this device"
            id == null -> "no on-device model is selected (pick one in Models)"
            meta == null -> "the selected on-device model is no longer on this device (pick one in Models)"
            else -> null
        }
        return RouteCandidate.Device(id.orEmpty(), meta?.displayName ?: id ?: "none selected", meta?.contextTokens, problem)
    }

    private fun pc(saved: InferenceSettings, policy: InferencePolicy): RouteCandidate.Pc? {
        val kind = saved.pcKind ?: fallbackPcKind()
            ?: PcHostKind.entries.firstOrNull { runCatching { pcHosts.load(it) }.getOrNull()?.target(it) != null }
        val target = kind?.let { k -> runCatching { pcHosts.load(k) }.getOrNull()?.target(k) }
        if (kind == null || target == null) {
            // Shown as a skipped place, so the owner sees why the PC was not used.
            val placeholder = PcHostTarget(kind ?: PcHostKind.OLLAMA, NO_ADDRESS, "none selected")
            return RouteCandidate.Pc(
                placeholder,
                PcModelFacts(),
                ProviderType.SELF_HOSTED,
                "no PC server and model are saved (Settings, PC-hosted models)",
            ).let { it.copy(target = placeholder) }
        }
        val key = runCatching { vault.getSecret(kind.secretId) }.getOrNull()?.takeIf { it.isNotBlank() }
        val facts = try {
            inspectPc(target, key)
        } catch (e: Exception) {
            PcModelFacts(problem = "the PC could not be asked about the model (${e.message ?: e.javaClass.simpleName})")
        }
        if (!facts.reachable) return RouteCandidate.Pc(target, facts, ProviderType.SELF_HOSTED, facts.problem)
        // The saved pick's location is only used to make the decision stricter, never to relax it.
        val location = when {
            target.location == PcModelLocation.CLOUD_BACKED || facts.location == PcModelLocation.CLOUD_BACKED -> PcModelLocation.CLOUD_BACKED
            else -> facts.location
        }
        val type = if (location == PcModelLocation.SERVER_LOCAL) ProviderType.SELF_HOSTED else ProviderType.CLOUD
        val refused = when {
            type == ProviderType.SELF_HOSTED || policy.permits(ProviderType.CLOUD) -> null
            location == PcModelLocation.CLOUD_BACKED ->
                "this model is cloud-backed: the PC would forward the conversation to ${facts.upstream ?: target.upstream ?: "an internet service"}, " +
                    "which this policy does not allow (pick a model that runs on the PC)"
            else ->
                "this server does not establish where the model runs, so it cannot be treated as your PC under this policy " +
                    "(use Ollama or a llama.cpp server, which report it, or choose the policy with cloud fallback)"
        }
        return RouteCandidate.Pc(target.copy(location = location, upstream = facts.upstream ?: target.upstream), facts, type, refused)
    }

    private fun cloud(): RouteCandidate.Cloud {
        val choice = cloudChoice()
        if (choice == null) {
            return RouteCandidate.Cloud(NO_CLOUD, "none selected", "no cloud provider is selected in Chat")
        }
        val key = runCatching { vault.getSecret(choice.spec.secretId) }.getOrNull()
        return RouteCandidate.Cloud(choice.spec, choice.model.trim().ifEmpty { choice.spec.defaultModel }, if (key.isNullOrBlank()) "no API key is saved for ${choice.spec.label} (Settings)" else null)
    }

    /** Stands in for the provider in the selector's registry: the selector decides, the caller builds and dispatches. */
    private object NotDispatched : LlmProvider {
        override val config = LlmConfig(provider = "route", model = "route")

        override fun complete(request: LlmRequest): LlmResponse =
            LlmResponse.Error(LlmError.InvalidResponse("A route placeholder was dispatched; this is a bug."))
    }

    private companion object {
        val NO_ADDRESS = ai.droidcommand.llm.pchost.PcHostAddress(base = "no address saved", host = "", https = false)
        val NO_CLOUD = CloudProviderSpec("none", "no provider", "", "")
    }
}
