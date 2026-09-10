package ai.droidcommand.root

/**
 * Reported capability state for a [RootProvider], matching the vocabulary
 * `docs/CAPABILITY_ROADMAP_PROMPT.md`'s capability-manager sections use.
 * `ENABLED`/`DISABLED` are deliberately never returned by any provider in
 * this package: whether root is *enabled for the session* is a
 * `core-security.SecurityPolicy.rootEnabled` decision, a layer above any
 * individual provider — a provider only ever reports what it can actually
 * observe about itself.
 */
enum class RootProviderState {
    AVAILABLE,
    ENABLED,
    DISABLED,
    UNAVAILABLE,
    REQUIRES_PERMISSION,
    REQUIRES_ROOT,
    REQUIRES_CONFIGURATION,
    ERROR,
}

enum class PrivilegeLevel {
    NONE,
    USER,
    ROOT,
}

data class RootProviderInfo(
    val providerId: String,
    val providerName: String,
    val version: String?,
)

data class RootHealth(
    val state: RootProviderState,
    val rootAvailable: Boolean,
    val rootAuthorized: Boolean,
    val rootShellAvailable: Boolean,
    val privilegeLevel: PrivilegeLevel,
    val lastError: String?,
)

/**
 * The generic, capability-driven root abstraction the core application
 * depends on instead of any specific root implementation (Magisk,
 * KernelSU, APatch, ...) — CAP-015's "provider/adapter architecture"
 * applied to root specifically. Deliberately extends [RootExecutor] rather
 * than redeclaring `isAvailable()`/`executePrivilegedCommand()` under new
 * names: [RootExecutor.isRootAvailable] and [RootExecutor.execute] are
 * already the exact methods `core-security.SecurityPolicy.rootAvailable`
 * and `PolicyEnforcingRootExecutor`/`RootTool` are built against and
 * tested through, so every [RootProvider] is automatically usable
 * anywhere a [RootExecutor] already is, with zero adapter code — the
 * "reuse existing abstractions where technically appropriate" rule this
 * addition was built under.
 *
 * A [RootProvider] adds three things a bare [RootExecutor] doesn't have:
 * provider identity ([info]), a distinction between "is a root mechanism
 * present" ([RootExecutor.isRootAvailable]) and "has it actually been
 * authorized" ([isAuthorized]) — never conflate the two; the whole point
 * of this type is that Magisk (or any other provider) being installed is
 * not proof it is usable — and a structured [checkHealth] report for
 * diagnostics/UI, once one exists, to render truthfully rather than
 * fabricating availability.
 */
interface RootProvider : RootExecutor {
    val info: RootProviderInfo

    /**
     * Whether this specific request has actually been authorized by the
     * root mechanism itself (e.g. Magisk's own superuser grant database).
     * This is deliberately a different question from
     * `core-security.SecurityPolicy.rootEnabled`/`grantedPermissions`,
     * which is DroidCommand AI's own, separate policy gate — a root
     * mechanism can authorize this UID while DroidCommand AI's own policy
     * still denies the tool, and vice versa; both must say yes.
     */
    fun isAuthorized(): Boolean

    fun getPrivilegeLevel(): PrivilegeLevel

    /**
     * [probeShell] defaults to `false`: a passive health check (presence
     * detection only) that spawns no process capable of interacting with
     * a real root shell — on a real device, invoking `su` for an app that
     * has not yet been granted access can itself trigger a system
     * authorization prompt, which a routine/automatic health check must
     * never cause silently (rule 13's "do not silently execute root
     * commands" applies here too, not just to agent-directed commands).
     * Pass `true` only when the caller has an explicit reason to actually
     * verify root-shell functionality right now.
     */
    fun checkHealth(probeShell: Boolean = false): RootHealth

    fun getCapabilities(): Set<String>
}
