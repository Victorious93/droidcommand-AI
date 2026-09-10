package ai.droidcommand.agent

/**
 * Fills in `ContextKind.PERSONA` (reserved, empty, since CAP-001) with a
 * real adapter (CAP-005). [activePersona] is a caller-supplied policy
 * decision — deciding *which* persona is active stays outside this class,
 * the same boundary [KnowledgeContextProvider]'s own `query` function
 * already draws.
 *
 * **This is P0.5's "Critical Isolation" requirement, enforced by
 * construction rather than a bolted-on check:** a [Persona] only ever
 * reaches anything else through this provider emitting one
 * [ContextContribution] under [ContextKind.PERSONA] — the exact same
 * plain-text channel every other context kind already uses. Nothing here
 * touches `SecurityPolicy`/`ToolSpec`/`Initiator`/`SecureToolExecutor` —
 * there is no such path to close off, because none is ever opened.
 *
 * A `null` or `enabled == false` persona contributes nothing, the same
 * [ContextProvider] convention every other provider uses for "nothing to
 * contribute right now."
 */
class PersonaContextProvider(private val activePersona: () -> Persona?) : ContextProvider {
    override fun provide(task: Task?): ContextContribution? {
        val persona = activePersona() ?: return null
        if (!persona.enabled) return null
        return ContextContribution(ContextKind.PERSONA, "persona:${persona.id}", persona.contextContribution)
    }
}
