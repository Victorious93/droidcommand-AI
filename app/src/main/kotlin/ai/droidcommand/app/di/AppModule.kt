package ai.droidcommand.app.di

import ai.droidcommand.agent.AgentStateMachine
import ai.droidcommand.agent.PermissionCategory
import ai.droidcommand.agent.ToolExecutor
import ai.droidcommand.agent.ToolRegistry
import ai.droidcommand.agent.ToolRunner
import ai.droidcommand.app.approval.ComposeApprovalPrompt
import ai.droidcommand.app.security.createAndroidSecretsVault
import ai.droidcommand.config.EncryptedSecretsVault
import android.content.Context
import ai.droidcommand.metasploit.MetasploitTool
import ai.droidcommand.metasploit.NullMetasploitExecutor
import ai.droidcommand.root.NullRootExecutor
import ai.droidcommand.root.RootTool
import ai.droidcommand.security.ApprovalPrompt
import ai.droidcommand.security.SecureToolExecutor
import ai.droidcommand.security.SecurityPolicy
import ai.droidcommand.security.SecurityPolicyEnforcer
import ai.droidcommand.setoolkit.NullSetExecutor
import ai.droidcommand.setoolkit.SetTool
import ai.droidcommand.shell.ProcessBuilderShellExecutor
import ai.droidcommand.shell.ShellSecurityPolicy
import ai.droidcommand.shell.ShellTool
import ai.droidcommand.termux.NullTermuxExecutor
import ai.droidcommand.termux.TermuxTool
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The Android-native equivalent of `cli.buildSession` — same tools, same
 * fail-closed defaults (every executor is a `Null*` until a real backend is
 * configured), wired through Hilt instead of a plain constructor function
 * so Compose screens can `hiltViewModel()` their way to a shared,
 * singleton [ToolRunner].
 *
 * [MetasploitTool]/[SetTool] are registered exactly like [RootTool]/
 * [TermuxTool]: `SecurityLevel.SENSITIVE`, always confirmed via
 * [ComposeApprovalPrompt], denied by default until an operator wires in a
 * real `ShellBackedMetasploitExecutor`/`ProcessBackedSetExecutor`.
 *
 * **Deliberately still Null-backed here, unlike `cli.buildSession`
 * (updated to an opt-in `DROIDCOMMAND_CLI_METASPLOIT`/`DROIDCOMMAND_CLI_SETOOLKIT`
 * env-var switch).** That switch doesn't transfer to this module for two
 * real reasons, not oversight: (1) there is no persisted Settings/config
 * layer here yet for a user to safely toggle this from — that is Phase 1's
 * own "API key entry... Settings screen" scope, not this Phase 0 shell's;
 * or an env var Android apps aren't normally configured through the way a
 * CLI process is. (2) `msfconsole`/`setoolkit` are Ruby/Python tools a
 * bare Android app process cannot `exec` unless they are themselves
 * reachable inside that process's own filesystem/PATH (e.g. a rooted
 * device or a Termux-hosted install) — a materially different, and
 * currently unverified, precondition from `cli`'s "runs on a PC that may
 * have msfconsole installed" assumption. Wiring a real backend in here is
 * real future work once Phase 1's Settings/secrets layer exists to gate
 * it, not something this entry silently defers without saying so.
 * [ShellTool]'s [ShellSecurityPolicy] is empty (no allowed executables)
 * for the same fail-closed reason `cli`'s own default is empty — a real
 * deployment configures it explicitly, this module never invents a "safe
 * commands" allowlist on the operator's behalf.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideToolRegistry(): ToolRegistry = ToolRegistry().apply {
        register(ShellTool(ProcessBuilderShellExecutor(ShellSecurityPolicy())))
        register(RootTool(NullRootExecutor()))
        register(TermuxTool(NullTermuxExecutor()))
        register(MetasploitTool(NullMetasploitExecutor()))
        register(SetTool(NullSetExecutor()))
    }

    /** Phase 1: Keystore-backed secrets (API keys). UNBUILT — see security/AndroidSecretsVault.kt. */
    @Provides
    @Singleton
    fun provideSecretsVault(@ApplicationContext context: Context): EncryptedSecretsVault = createAndroidSecretsVault(context)

    @Provides
    @Singleton
    fun provideAgentStateMachine(): AgentStateMachine = AgentStateMachine()

    @Provides
    @Singleton
    fun provideSecurityPolicy(): SecurityPolicy =
        SecurityPolicy(grantedCategories = setOf(PermissionCategory.TERMINAL, PermissionCategory.NETWORK))

    @Provides
    @Singleton
    fun provideApprovalPrompt(composePrompt: ComposeApprovalPrompt): ApprovalPrompt = composePrompt

    @Provides
    @Singleton
    fun provideToolRunner(
        registry: ToolRegistry,
        stateMachine: AgentStateMachine,
        policy: SecurityPolicy,
        approvalPrompt: ApprovalPrompt,
    ): ToolRunner {
        val delegate = ToolExecutor(registry, stateMachine)
        return SecureToolExecutor(registry, delegate, stateMachine, SecurityPolicyEnforcer(policy), approvalPrompt)
    }
}
