package ai.droidcommand.security

import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.ToolSpec

/**
 * The 13 permission categories P1.2 names, verbatim — a real, typed
 * vocabulary a caller can use to populate the already-shipped
 * `ToolSpec.requiredPermissions`/`SecurityPolicy.grantedPermissions`
 * (both `Set<String>`, unchanged by this file — see
 * [validateRootEquivalentPermissions]'s own doc for why those fields'
 * types are deliberately left alone).
 *
 * [isRootEquivalent] is the concrete, enforceable form of P1.2's own
 * CRITICAL callout: "Docker/container socket access is root-equivalent.
 * Never present `CONTAINER` as a peer permission to `VIEW`/`AUTOMATION`."
 * Only [ROOT] (obviously) and [CONTAINER] (per that explicit callout) are
 * marked true — [VIRTUALIZATION]/[INFRASTRUCTURE] are left ordinary since
 * the spec extends no such explicit warning to them, and assuming they
 * deserve the same treatment would be an unstated guess this project's
 * rules forbid.
 */
enum class PermissionCategory(val isRootEquivalent: Boolean = false) {
    VIEW,
    AUTOMATION,
    TERMINAL,
    FILES,
    NETWORK,
    AI,
    REMOTE_CONTROL,
    DEVICE_CONTROL,
    KNOWLEDGE_GRAPH,
    ROOT(isRootEquivalent = true),
    CONTAINER(isRootEquivalent = true),
    VIRTUALIZATION,
    INFRASTRUCTURE,
}

/**
 * The concrete, testable enforcement of P1.2's CRITICAL rule: a tool
 * whose [ToolSpec.requiredPermissions] names a root-equivalent
 * [PermissionCategory] must itself be declared [SecurityLevel.ROOT] — it
 * can never be presented as merely `NORMAL`/`SENSITIVE` while requiring
 * root-equivalent access.
 *
 * **Only permission strings that actually name a [PermissionCategory]
 * participate** — free-form runtime-permission strings already in use
 * elsewhere in this codebase (e.g. existing tools' `"CAMERA"`/
 * `"MICROPHONE"`) are a separate, pre-existing vocabulary sharing the same
 * `Set<String>` field, and are silently ignored by this check rather than
 * conflated with it.
 *
 * Reuses the existing, unmodified [PolicyDecision]/[ToolSpec]/
 * [SecurityLevel] types — no new parallel result type. **Deliberately not
 * wired into [SecurityPolicyEnforcer.authorize]** — that method, and its
 * existing tests, are untouched by this file; a caller who wants both
 * checks composes them explicitly, the same restraint
 * `AgentRouterGateway` already applies to composing
 * `checkCapabilityAvailability` with `authorize`.
 */
fun validateRootEquivalentPermissions(spec: ToolSpec): PolicyDecision {
    val rootEquivalentRequired = spec.requiredPermissions
        .mapNotNull { name -> PermissionCategory.entries.find { it.name == name } }
        .filter { it.isRootEquivalent }

    if (rootEquivalentRequired.isNotEmpty() && spec.securityLevel != SecurityLevel.ROOT) {
        return PolicyDecision.Deny(
            "Tool '${spec.name}' requires root-equivalent permission(s) " +
                "${rootEquivalentRequired.joinToString { it.name }} but is declared ${spec.securityLevel}, not ROOT",
        )
    }

    return PolicyDecision.Allow
}

/**
 * P1.2's own 7-step "escalation preference order," verbatim, in
 * declaration order — the same "declaration order is the priority order"
 * discipline `ContextKind`/`CapabilityState` already establish, so
 * [entries] alone drives [leastPrivilegedAvailable] without a separate
 * rank field.
 *
 * **A real nuance, stated rather than smoothed over:** the spec's own
 * note says "Termux is not a privilege tier; it's a separate execution
 * environment... Shizuku requires ADB or root to bootstrap; it's the API
 * surface, not the privilege grant." This enum still treats all 7 as one
 * ordered preference list, exactly as the spec's own single list does —
 * it does not pretend they're homogeneous privilege tiers, only that this
 * is the order to prefer them in.
 */
enum class EscalationTier {
    ANDROID_SYSTEM_API,
    ACCESSIBILITY_SERVICE,
    ANDROID_RUNTIME_PERMISSION,
    TERMUX,
    SHIZUKU,
    ADB,
    ROOT,
}

/**
 * The concrete implementation of least-privilege selection over
 * [EscalationTier]'s ladder: the first (least-privileged) tier present in
 * [available], or `null` when nothing in [available] is a real
 * [EscalationTier] — an honest "nothing available" signal, never a
 * fabricated fallback tier. Real detection of which tiers are actually
 * available on a given device/environment is a caller concern — this
 * environment has no Android SDK/device/Termux/Shizuku/ADB to detect
 * against.
 */
fun leastPrivilegedAvailable(available: Set<EscalationTier>): EscalationTier? = EscalationTier.entries.firstOrNull { it in available }
