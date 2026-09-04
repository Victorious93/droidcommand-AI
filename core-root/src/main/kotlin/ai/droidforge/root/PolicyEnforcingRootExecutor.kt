package ai.droidforge.root

/**
 * Enforces [RootSecurityPolicy]'s executable allow-list in front of any
 * [RootExecutor] — real logic, not a stub, independent of whether
 * [delegate] is [NullRootExecutor] today or a real rooted-device executor
 * later. A command outside the allow-list never reaches [delegate] at
 * all, the same "no side effect on denial" guarantee
 * `core-build.WorkspaceManager` and `core-shell.ProcessBuilderShellExecutor`
 * already provide for their own domains.
 */
class PolicyEnforcingRootExecutor(
    private val policy: RootSecurityPolicy,
    private val delegate: RootExecutor,
) : RootExecutor {
    override fun isRootAvailable(): Boolean = delegate.isRootAvailable()

    override fun execute(command: RootCommand, isCancelled: () -> Boolean): RootExecutionResult {
        if (command.executable !in policy.allowedExecutables) {
            return RootExecutionResult.Failure("Executable '${command.executable}' is not in the allowed root executable list")
        }
        return delegate.execute(command, isCancelled)
    }
}
