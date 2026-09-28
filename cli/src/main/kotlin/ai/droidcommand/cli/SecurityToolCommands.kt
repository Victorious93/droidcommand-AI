package ai.droidcommand.cli

import ai.droidcommand.metasploit.MetasploitExecutor
import ai.droidcommand.metasploit.NullMetasploitExecutor
import ai.droidcommand.metasploit.ShellBackedMetasploitExecutor
import ai.droidcommand.setoolkit.NullSetExecutor
import ai.droidcommand.setoolkit.ProcessBackedSetExecutor
import ai.droidcommand.setoolkit.SetExecutor
import ai.droidcommand.shell.ProcessBuilderShellExecutor

internal const val METASPLOIT_ENV = "DROIDCOMMAND_CLI_METASPLOIT"
internal const val METASPLOIT_PATH_ENV = "DROIDCOMMAND_CLI_METASPLOIT_PATH"
internal const val SETOOLKIT_ENV = "DROIDCOMMAND_CLI_SETOOLKIT"
internal const val SETOOLKIT_PATH_ENV = "DROIDCOMMAND_CLI_SETOOLKIT_PATH"

/**
 * The executor behind `run_metasploit_module`: [ShellBackedMetasploitExecutor] when
 * [METASPLOIT_ENV] is `1`/`true`, otherwise [NullMetasploitExecutor], which fails cleanly. The
 * same opt-in-only pattern [AdbDeviceConfig.fromEnvironment] already establishes for
 * `run_termux_command` — off unless explicitly enabled, so this CLI never reaches for a real
 * `msfconsole` by surprise.
 *
 * Reuses [shellSecurityPolicy] rather than inventing a second, parallel allowlist — the same
 * shared-allowlist precedent `buildPipeline`'s own doc comment already states for `build_project`.
 * That means enabling [METASPLOIT_ENV] alone is not enough: the exact configured
 * [METASPLOIT_PATH_ENV] value (or the `"msfconsole"` default) must also appear in
 * `DROIDCOMMAND_CLI_SHELL_ALLOWED_EXECUTABLES`, or [ProcessBuilderShellExecutor]'s own fail-closed
 * check denies every invocation before any process starts — enabling the tool and authorizing the
 * binary are deliberately two separate switches, not one.
 */
internal fun metasploitExecutorFor(env: (String) -> String? = System::getenv): MetasploitExecutor {
    val enabled = env(METASPLOIT_ENV)?.trim()?.lowercase()
    if (enabled != "1" && enabled != "true") return NullMetasploitExecutor()
    val path = env(METASPLOIT_PATH_ENV)?.takeIf { it.isNotBlank() } ?: "msfconsole"
    return ShellBackedMetasploitExecutor(ProcessBuilderShellExecutor(shellSecurityPolicy()), path)
}

/**
 * The `run_setoolkit_attack` counterpart to [metasploitExecutorFor]: [ProcessBackedSetExecutor]
 * when [SETOOLKIT_ENV] is `1`/`true`, otherwise [NullSetExecutor]. Same shared-allowlist
 * requirement as Metasploit's — [SETOOLKIT_PATH_ENV] (or the `"setoolkit"` default) must be in
 * `DROIDCOMMAND_CLI_SHELL_ALLOWED_EXECUTABLES` too. Uses [ProcessBackedSetExecutor]'s own
 * `defaultInvocationBuilder` rather than a CLI-specific one — that default is already explicitly
 * labeled `[Guessing]` in its own doc comment (SET's non-interactive automation interface was not
 * verified against a real installation); this CLI does not add a second guess on top of it.
 */
internal fun setExecutorFor(env: (String) -> String? = System::getenv): SetExecutor {
    val enabled = env(SETOOLKIT_ENV)?.trim()?.lowercase()
    if (enabled != "1" && enabled != "true") return NullSetExecutor()
    val path = env(SETOOLKIT_PATH_ENV)?.takeIf { it.isNotBlank() } ?: "setoolkit"
    return ProcessBackedSetExecutor(ProcessBuilderShellExecutor(shellSecurityPolicy()), path)
}
