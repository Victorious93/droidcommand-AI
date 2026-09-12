package ai.droidcommand.agent

/**
 * CAP-010, P1.2. The permission categories the roadmap prompt's own P1.2
 * section lists verbatim (13 values — an earlier `docs/AUDIT_2026-09-05.md`
 * addendum's own CAP-010 row miscounted this as 12; the literal list has
 * 13, corrected here rather than perpetuated). This is a *category*
 * axis — what kind of capability something is — orthogonal to
 * [SecurityLevel] (how much confirmation invoking it needs) and `RiskTier`
 * (how reversible it is): the same "distinct axis, not a competing model"
 * relationship `RiskTier` and [SecurityLevel] already have. Nothing here
 * folds a 14th value into [SecurityLevel] the way a literal reading of the
 * prompt's own "extend the existing `SecurityLevel` enum" heading might
 * suggest — doing that would break every existing use of [SecurityLevel]
 * as a 3-tier confirmation-requirement axis for a wholly different
 * "content type of the permission" concept.
 *
 * Lives in `core-agent`, not `core-security`, alongside [SecurityLevel] and
 * [Initiator] — [ToolSpec] (this module) needs to reference it directly,
 * and `core-security` already depends on `core-agent`, not the reverse, so
 * a type a `ToolSpec` field carries cannot live on the other side of that
 * dependency. `core-security`'s `SecurityPolicy.isCategoryGranted` and the
 * root-equivalence gate below still consult it the same way; only the type
 * declaration moved.
 */
enum class PermissionCategory {
    VIEW,
    AUTOMATION,
    TERMINAL,
    FILES,
    NETWORK,
    AI,
    REMOTE_CONTROL,
    DEVICE_CONTROL,
    KNOWLEDGE_GRAPH,
    ROOT,
    CONTAINER,
    VIRTUALIZATION,
    INFRASTRUCTURE,
}

/**
 * The roadmap prompt's own "CRITICAL: Docker/container socket access is
 * root-equivalent. Never present `CONTAINER` as a peer permission to
 * `VIEW`/`AUTOMATION`" instruction, made an executable gate via
 * `core-security`'s `SecurityPolicy.isCategoryGranted` rather than left as
 * a comment only — the prompt's own "Document this explicitly in the
 * policy engine" instruction, taken at its strongest.
 */
val ROOT_EQUIVALENT_CATEGORIES: Set<PermissionCategory> = setOf(PermissionCategory.ROOT, PermissionCategory.CONTAINER)
