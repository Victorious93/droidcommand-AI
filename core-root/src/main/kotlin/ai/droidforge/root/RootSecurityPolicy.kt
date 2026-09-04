package ai.droidforge.root

/**
 * A second, narrower layer beneath `core-security`'s session-level root
 * gate (`SecurityPolicy.rootEnabled`/`rootAvailable`): that gate decides
 * whether root Tool invocations are authorized *at all* this session;
 * this policy decides *which specific commands* an authorized root
 * invocation may actually run. Fail-closed the same way
 * `core-shell.ShellSecurityPolicy` is: an empty [allowedExecutables] means
 * nothing runs until explicitly permitted, even once the session-level
 * gate has already said yes.
 */
data class RootSecurityPolicy(val allowedExecutables: Set<String> = emptySet())
