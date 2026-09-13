package ai.droidcommand.termux

/**
 * The extension point for actually running a command inside a Termux
 * userland — mirrors `core-root.RootExecutor`/`core-shell.ShellExecutor`'s
 * role in those modules. Deliberately a distinct capability from
 * [ai.droidcommand.root.RootExecutor]: Termux is an unprivileged (from
 * this application's own policy standpoint) execution surface — a real
 * Linux userland reachable without root — not a root mechanism, even
 * though the one real implementation in this module ([AdbTermuxExecutor])
 * happens to depend on root being available *elsewhere* purely to read a
 * command's output back (an implementation detail of that one backend, not
 * a property of this interface — see its own doc comment). [isAvailable]
 * is meant to be called cheaply/passively, mirroring
 * [ai.droidcommand.root.RootExecutor.isRootAvailable]'s role feeding
 * `core-security.SecurityPolicy`. [NullTermuxExecutor] is the only
 * always-present implementation; [AdbTermuxExecutor] is real but requires
 * a real adb-connected device with Termux installed, which this
 * environment does not have.
 */
interface TermuxExecutor {
    fun isAvailable(): Boolean

    fun execute(command: TermuxCommand, isCancelled: () -> Boolean = { false }): TermuxExecutionResult
}
