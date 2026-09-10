package ai.droidcommand.root

/**
 * The default [RootProvider] when no real root mechanism is configured —
 * same honesty rule as [NullRootExecutor]/`core-tools-android.NullDeviceController`:
 * every state is truthfully "not available", and [execute] fails explicitly
 * rather than fabricating a successful elevated command.
 */
class NullRootProvider : RootProvider {
    override val info = RootProviderInfo(providerId = "none", providerName = "No root provider configured", version = null)

    override fun isRootAvailable(): Boolean = false

    override fun isAuthorized(): Boolean = false

    override fun getPrivilegeLevel(): PrivilegeLevel = PrivilegeLevel.NONE

    override fun checkHealth(probeShell: Boolean): RootHealth = RootHealth(
        state = RootProviderState.UNAVAILABLE,
        rootAvailable = false,
        rootAuthorized = false,
        rootShellAvailable = false,
        privilegeLevel = PrivilegeLevel.NONE,
        lastError = "No root provider is configured (NullRootProvider)",
    )

    override fun getCapabilities(): Set<String> = emptySet()

    override fun execute(command: RootCommand, isCancelled: () -> Boolean): RootExecutionResult =
        RootExecutionResult.Failure("Cannot run '${command.executable}' as root: no root provider is configured (NullRootProvider)")
}
