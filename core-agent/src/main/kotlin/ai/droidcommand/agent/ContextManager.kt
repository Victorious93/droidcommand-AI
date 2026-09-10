package ai.droidcommand.agent

/**
 * Every kind of context `docs/CAPABILITY_ROADMAP_PROMPT.md`'s P0.1 (CAP-001)
 * names, in priority order — **declaration order is the priority order**
 * (lower ordinal wins), the same "fix iteration order explicitly, never rely
 * on incidental ordering" discipline [TaskGraph.from] already applies to its
 * own determinism. The order follows P0.2's own stated priority list
 * (current task, relevant local/device/tool/execution/user context, project,
 * persona, knowledge, summaries, older conversation history *last*) —
 * deliberately putting raw conversation scrollback below structured
 * knowledge/summaries, exactly as that section specifies.
 *
 * [mandatory] kinds are never dropped for budget, even if they alone exceed
 * it — see [DefaultContextManager.buildContext].
 */
enum class ContextKind(val mandatory: Boolean = false) {
    TASK(mandatory = true),
    SYSTEM_INSTRUCTIONS(mandatory = true),
    DEVICE,
    TOOL,
    EXECUTION,
    USER,
    PROJECT,
    PERSONA,
    KNOWLEDGE,
    SUMMARY,
    CONVERSATION,
}

/**
 * One provider's contribution to a [ContextSnapshot]: a block of text under
 * a [kind], attributed to [sourceId] (e.g. a provider's class name or a
 * caller-chosen label) so [ContextInspection] can show where each piece of
 * context came from. [estimatedTokens] reuses [estimateTokens] (the same
 * ~4-chars/token heuristic [ConversationContext] already uses) unless a
 * caller supplies a better estimate.
 */
data class ContextContribution(
    val kind: ContextKind,
    val sourceId: String,
    val content: String,
    val estimatedTokens: Int = estimateTokens(content),
)

/**
 * A source of context a [ContextManager] can aggregate. Returning `null`
 * means "nothing to contribute for this [task] right now" — not an error,
 * and not an empty-string placeholder that would still consume a slot in a
 * [ContextSnapshot].
 */
fun interface ContextProvider {
    fun provide(task: Task?): ContextContribution?
}

/**
 * A [ContextProvider] whose content never varies by task — e.g. a fixed
 * system-instructions string. Trivial, but named so call sites read as
 * intent ("this content is static") rather than a lambda that happens to
 * ignore its argument.
 */
class StaticContextProvider(
    private val kind: ContextKind,
    private val sourceId: String,
    private val content: String,
) : ContextProvider {
    override fun provide(task: Task?): ContextContribution? =
        if (content.isEmpty()) null else ContextContribution(kind, sourceId, content)
}

/**
 * The result of [ContextManager.buildContext]: [included] contributions fit
 * within [tokenBudget] (mandatory kinds are included regardless, see
 * [DefaultContextManager]); [omitted] contributions were dropped for budget,
 * not silently discarded — this is P0.1/P0.2's own "provide token/context
 * visibility where practical" requirement, made concrete rather than only
 * reported as a total.
 */
data class ContextSnapshot(
    val task: Task?,
    val included: List<ContextContribution>,
    val omitted: List<ContextContribution>,
    val tokenBudget: Int,
    val tokenBudgetUsed: Int,
    val tokenBudgetRemaining: Int,
)

/** Registered-provider counts per [ContextKind], plus the most recent [ContextSnapshot] built, if any. */
data class ContextInspection(
    val registeredProviders: Map<ContextKind, Int>,
    val lastSnapshot: ContextSnapshot?,
)

/**
 * Aggregates context from registered [ContextProvider]s into one prioritized
 * [ContextSnapshot] within a token budget (CAP-001, P0.1).
 *
 * **Deliberate deviation from the roadmap prompt's literal spec, stated
 * plainly rather than silently:** P0.1 specifies a fixed-field
 * `ContextSnapshot` with a `userContext: UserContext`, `projectContext:
 * ProjectContext`, `activePersona: Persona?`, `knowledgeGraphContext:
 * KnowledgeGraphSnapshot` etc. — none of those types exist anywhere in this
 * repository (confirmed directly against `docs/AUDIT_2026-09-05.md`'s
 * CAP-005/006/007 rows, all MISSING). Defining them as always-empty structs
 * just to satisfy the interface shape would be exactly the kind of
 * fabricated-status scaffolding this codebase's `Null*`-explicit-failure
 * convention exists to avoid. Instead, [ContextKind] names every concept the
 * spec calls for so [registerProvider] is a real, ready extension point for
 * all of them, but only kinds with genuine data behind them ship a built-in
 * [ContextProvider] today (see the provider classes in this module and
 * `core-tools-android.DeviceContextProvider`) — the others become real the
 * moment a future slice (CAP-005/006/007) registers one, with zero change
 * to this interface.
 */
interface ContextManager {
    fun registerProvider(kind: ContextKind, provider: ContextProvider)

    fun buildContext(task: Task?, tokenBudget: Int): ContextSnapshot

    fun inspectContext(): ContextInspection
}

/**
 * The real [ContextManager] implementation. Thread-safety mirrors
 * [ToolRegistry]'s provider map (`synchronized` on registration/lookup) and
 * [AgentStateMachine.state]'s cross-thread visibility reasoning for
 * [lastSnapshot] (`@Volatile`, since a status display could poll
 * [inspectContext] from a different thread than the one building context).
 */
class DefaultContextManager : ContextManager {
    private val lock = Any()
    private val providers = mutableMapOf<ContextKind, MutableList<ContextProvider>>()

    @Volatile
    private var lastSnapshot: ContextSnapshot? = null

    override fun registerProvider(kind: ContextKind, provider: ContextProvider) {
        synchronized(lock) {
            providers.getOrPut(kind) { mutableListOf() }.add(provider)
        }
    }

    /**
     * Calls every registered provider (in [ContextKind] declaration order,
     * ties broken by registration order — deterministic across calls with
     * identical input, the same discipline [TaskGraph.from] documents for
     * its own [TaskGraph.executionOrder]), plus a [Task]-derived [ContextKind.TASK]
     * contribution synthesized internally (no registration needed — a
     * [task] is always the current task the moment one is passed).
     *
     * [ContextKind.mandatory] contributions ([ContextKind.TASK],
     * [ContextKind.SYSTEM_INSTRUCTIONS]) are always included even if they
     * alone exceed [tokenBudget] — the same "never evict the thing that
     * would silence the current turn" rule [ConversationContext.append]
     * already applies to its own just-appended message. Everything else is
     * included greedily in priority order until the budget is exhausted;
     * the remainder is [ContextSnapshot.omitted], not silently dropped.
     */
    override fun buildContext(task: Task?, tokenBudget: Int): ContextSnapshot {
        require(tokenBudget > 0) { "tokenBudget must be > 0, got $tokenBudget" }

        val snapshotProviders = synchronized(lock) { providers.mapValues { it.value.toList() } }

        val taskContribution = task?.let {
            val criteria = if (it.verificationCriteria.isEmpty()) {
                ""
            } else {
                "\nVerification criteria: ${it.verificationCriteria.joinToString("; ")}"
            }
            ContextContribution(ContextKind.TASK, "task:${it.id}", "${it.description}$criteria")
        }

        val allContributions = buildList {
            taskContribution?.let(::add)
            for (kind in ContextKind.entries) {
                if (kind == ContextKind.TASK) continue
                for (provider in snapshotProviders[kind].orEmpty()) {
                    provider.provide(task)?.let(::add)
                }
            }
        }

        val included = mutableListOf<ContextContribution>()
        val omitted = mutableListOf<ContextContribution>()
        var used = 0
        for (contribution in allContributions) {
            val fits = contribution.kind.mandatory || used + contribution.estimatedTokens <= tokenBudget
            if (fits) {
                included += contribution
                used += contribution.estimatedTokens
            } else {
                omitted += contribution
            }
        }

        val snapshot = ContextSnapshot(
            task = task,
            included = included,
            omitted = omitted,
            tokenBudget = tokenBudget,
            tokenBudgetUsed = used,
            tokenBudgetRemaining = (tokenBudget - used).coerceAtLeast(0),
        )
        lastSnapshot = snapshot
        return snapshot
    }

    override fun inspectContext(): ContextInspection {
        val counts = synchronized(lock) { providers.mapValues { it.value.size } }
        return ContextInspection(counts, lastSnapshot)
    }
}

/** Formats [ConversationContext.messages] as one [ContextKind.CONVERSATION] contribution. */
class ConversationContextProvider(private val context: ConversationContext) : ContextProvider {
    override fun provide(task: Task?): ContextContribution? {
        if (context.messages.isEmpty()) return null
        val formatted = context.messages.joinToString("\n") { "${it.role}: ${it.content}" }
        return ContextContribution(ContextKind.CONVERSATION, "conversation", formatted)
    }
}

/**
 * Formats [KnowledgeEntry] results as one [ContextKind.KNOWLEDGE]
 * contribution via [formatKnowledgeContext]. Takes a [query] function rather
 * than a [KnowledgeStore] plus a fixed tag/keyword directly: **deciding what
 * to retrieve for a given task is the same LLM-shaped policy decision
 * [formatKnowledgeContext]'s own doc comment already keeps out of
 * `core-agent`** — this provider stays a mechanical formatter, and a caller
 * (today: a fixed tag/keyword lookup; later: something LLM-driven in
 * `core-llm`) supplies the query policy, the same boundary
 * `ObjectiveAnalyzer`/`ObjectiveEngine` already establish.
 */
class KnowledgeContextProvider(private val query: (Task?) -> List<KnowledgeEntry>) : ContextProvider {
    override fun provide(task: Task?): ContextContribution? {
        val entries = query(task)
        val formatted = formatKnowledgeContext(entries) ?: return null
        return ContextContribution(ContextKind.KNOWLEDGE, "knowledge", formatted)
    }
}
