package ai.droidforge.shell

/**
 * Fail-closed by default, the same way `core-security.SecurityPolicy`
 * defaults `rootEnabled` to false: [allowedExecutables] is empty, so
 * [ProcessBuilderShellExecutor] runs nothing at all until a caller
 * deliberately opts specific executables in. [allowedWorkingDirectories]
 * follows the same rule — if it's empty, a command may not specify a
 * working directory override at all (it runs in the executor's own
 * process working directory), rather than "empty means unrestricted."
 */
data class ShellSecurityPolicy(
    val allowedExecutables: Set<String> = emptySet(),
    val allowedWorkingDirectories: List<String> = emptyList(),
)
