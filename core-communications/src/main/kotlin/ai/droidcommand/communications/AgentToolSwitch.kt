package ai.droidcommand.communications

import ai.droidcommand.agent.DuplicateToolException
import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolRegistry
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolSpec

/**
 * Victorious Framework: a [Tool] that refuses to run while the owner's switch is off. The switch is read on EVERY call,
 * so flipping it off stops the tool even for a plan that was already made, and even if the registry was not updated yet.
 */
class GatedCommunicationsTool(private val inner: Tool, private val enabled: () -> Boolean) : Tool {
    override val spec: ToolSpec get() = inner.spec

    override fun execute(input: Map<String, String>): ToolResult =
        if (enabled()) inner.execute(input) else ToolResult.Failure(OFF)

    companion object {
        const val OFF = "Communications tools are switched off by the owner (Communications screen). Nothing was read or sent."
    }
}

/**
 * Victorious Framework: the owner's default-OFF switch for droidcommand-AI's OWN agent (Chat, Plan, Pilot, Forge).
 *
 * Why a switch: conversation text these tools return goes into the model the owner's inference policy routes to
 * (`docs/INFERENCE_POLICY.md`), which may be a PC or a confirmed cloud fallback. The tools are therefore not part of the
 * registry until the owner turns this on, and each one checks the switch again when it runs.
 *
 * What it offers: only capabilities this app can serve today ([offeredByDefault]: the three VictorSMS archive reads plus
 * `generate_reply` and `summarize_conversation`). Sends, calls, contacts, imports and automation are NOT offered, so the
 * owner is never asked to approve an action that could only be refused, and this switch cannot become a send path. The
 * constructor refuses any offered capability that is not a read or a draft.
 *
 * The VictorSMS side keeps its own two gates (the Archive read permission for this app, and the per-conversation
 * assistant switch); this switch does not widen either.
 */
class CommunicationsAgentTools(
    private val registry: ToolRegistry,
    broker: CommsBroker,
    private val enabled: () -> Boolean,
    offered: Set<String> = offeredByDefault(),
    private val persist: (Boolean) -> Unit = {},
) {
    private val tools: List<Tool>

    init {
        val caps = offered.map { id -> requireNotNull(CommsCatalog.get(id)) { "Unknown capability $id" } }
        val unsafe = caps.filter { it.risk != CommsRisk.READ && it.risk != CommsRisk.DRAFT }
        require(unsafe.isEmpty()) { "The agent switch offers reads and drafts only, not ${unsafe.joinToString { it.id }}." }
        tools = caps.map { GatedCommunicationsTool(CommunicationsTool(it, broker), enabled) }
    }

    /** What the owner's switch says right now. */
    val isEnabled: Boolean get() = enabled()

    /** Names of the tools this switch controls, in registration order. */
    val toolNames: List<String> get() = tools.map { it.spec.name }

    /**
     * Brings the registry in line with the switch: registers every offered tool while it is on, removes exactly those
     * tools while it is off. Safe to call any number of times, from any thread. A tool of the same name that this class
     * did not create is never replaced and never removed. Returns how many of ours are registered afterwards.
     */
    @Synchronized
    fun apply(): Int {
        val on = enabled()
        for (tool in tools) {
            val name = tool.spec.name
            val current = runCatching { registry.get(name) }.getOrNull()
            if (on && current == null) {
                try {
                    registry.register(tool)
                } catch (_: DuplicateToolException) {
                    // Someone else registered the name between the check and now; leave theirs alone.
                }
            } else if (!on && current === tool) {
                registry.unregister(name)
            }
        }
        return registeredCount()
    }

    /** The owner flips the switch: store it first, then bring the registry in line. Returns how many tools are registered afterwards. */
    @Synchronized
    fun setEnabled(on: Boolean): Int {
        persist(on)
        return apply()
    }

    fun registeredCount(): Int = tools.count { runCatching { registry.get(it.spec.name) }.getOrNull() === it }

    companion object {
        /** Capabilities the wired connectors can serve: VictorSMS's archive reads, plus the two the app itself answers with its model. */
        fun offeredByDefault(): Set<String> =
            VictorSmsCommsBackend.SERVED_CAPABILITIES + CommsCatalog.ALL.filter { it.owner == Owner.DROIDCOMMAND }.map { it.id }
    }
}
