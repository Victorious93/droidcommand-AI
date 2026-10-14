package ai.droidcommand.app.di

import ai.droidcommand.agent.AgentStateMachine
import ai.droidcommand.agent.ConversationStore
import ai.droidcommand.agent.KnowledgeGraph
import ai.droidcommand.agent.PermissionCategory
import ai.droidcommand.agent.ToolExecutor
import ai.droidcommand.agent.ToolRegistry
import ai.droidcommand.agent.ToolRunner
import ai.droidcommand.app.approval.ComposeApprovalPrompt
import ai.droidcommand.app.approval.VoiceApprovalPrompt
import ai.droidcommand.app.automation.AiPromptTool
import ai.droidcommand.app.automation.AllowlistShellExecutor
import ai.droidcommand.app.automation.AutomationShellTool
import ai.droidcommand.app.mode.AdvancedModeStore
import ai.droidcommand.app.mode.EngineModes
import ai.droidcommand.app.prefs.AdvancedPrefs
import ai.droidcommand.app.security.createAndroidSecretsVault
import ai.droidcommand.config.EncryptedSecretsVault
import ai.droidcommand.hackerai.HackerAiTool
import ai.droidcommand.conversations.RoomConversationStore
import ai.droidcommand.knowledge.android.RoomKnowledgeGraph
import ai.droidcommand.llm.factory.CloudPromptClient
import ai.droidcommand.llm.factory.CloudProviderSpec
import ai.droidcommand.metasploit.MetasploitTool
import ai.droidcommand.metasploit.NullMetasploitExecutor
import ai.droidcommand.remote.HttpTransport
import ai.droidcommand.remote.HttpUrlConnectionTransport
import ai.droidcommand.root.NullRootExecutor
import ai.droidcommand.root.RootTool
import ai.droidcommand.security.ApprovalPrompt
import ai.droidcommand.security.JsonFileAuditLog
import ai.droidcommand.security.SecureToolExecutor
import ai.droidcommand.security.SecurityPolicy
import ai.droidcommand.security.SecurityPolicyEnforcer
import ai.droidcommand.setoolkit.NullSetExecutor
import ai.droidcommand.setoolkit.SetTool
import ai.droidcommand.termux.NullTermuxExecutor
import ai.droidcommand.termux.TermuxTool
import ai.droidcommand.voice.android.AndroidSpeechToText
import ai.droidcommand.voice.android.AndroidTextToSpeech
import ai.droidcommand.voice.android.SharedPreferencesVoiceSettingsStore
import android.content.Context
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
 * env-var switch).** Settings states that no program is connected; it does
 * not offer a toggle that would pretend otherwise. `msfconsole`/`setoolkit`
 * are Ruby/Python tools a
 * bare Android app process cannot `exec` unless they are themselves
 * reachable inside that process's own filesystem/PATH (e.g. a rooted
 * device or a Termux-hosted install) — a materially different, and
 * currently unverified, precondition from `cli`'s "runs on a PC that may
 * have msfconsole installed" assumption. Wiring a real backend remains
 * future work; this module does not invent a connected one.
 * The shell allowlist starts empty (no executable is allowed) for the same
 * fail-closed reason `cli`'s own default is empty. The owner allows
 * executables on the Automation keys screen; this module never invents a
 * "safe commands" allowlist on the owner's behalf.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    private const val CHAT_PREFS = "chat_prefs"

    /**
     * Automation keys are the only callers of the shell and AI tools. Both have empty `allowedModes`, so the Pilot and Forge
     * planners never see them. The shell allowlist is read on every run and starts empty, so nothing runs until the owner
     * allows an executable on the Automation keys screen.
     */
    @Provides
    @Singleton
    fun provideToolRegistry(
        @ApplicationContext context: Context,
        vault: EncryptedSecretsVault,
        transport: HttpTransport,
        advanced: AdvancedPrefs,
    ): ToolRegistry = ToolRegistry().apply {
        register(AutomationShellTool(AllowlistShellExecutor { advanced.shellAllowlist() }))
        register(AiPromptTool(CloudPromptClient(vault, transport)) { spec -> chatModelFor(context, spec) })
        register(RootTool(NullRootExecutor()))
        register(TermuxTool(NullTermuxExecutor()))
        register(MetasploitTool(NullMetasploitExecutor()))
        register(SetTool(NullSetExecutor()))
        // Always-fail stub: AIDL bind is not started in this module. Registering it keeps the
        // name visible to anything that asks the registry; Pilot as DEVICE_OWNER still hides
        // it because the tool is scoped to Initiator.REMOTE.
        register(HackerAiTool())
    }

    /** The model chat uses for [spec], read from the same preference chat writes. Falls back to the provider's suggestion. */
    private fun chatModelFor(context: Context, spec: CloudProviderSpec): String =
        context.getSharedPreferences(CHAT_PREFS, Context.MODE_PRIVATE).getString("model.${spec.id}", null) ?: spec.defaultModel

    /** Phase 1: Keystore-backed secrets (API keys). UNBUILT — see security/AndroidSecretsVault.kt. */
    @Provides
    @Singleton
    fun provideSecretsVault(@ApplicationContext context: Context): EncryptedSecretsVault = createAndroidSecretsVault(context)

    @Provides
    @Singleton
    fun provideConversationStore(@ApplicationContext context: Context): ConversationStore = RoomConversationStore.create(context)

    /** Knowledge-graph phase K1 storage. Backs K2 (retrieval), K3 (extraction) and K4 (the Memory screen) — see docs/KNOWLEDGE_GRAPH_PHASE_SCOPE.md. */
    @Provides
    @Singleton
    fun provideKnowledgeGraph(@ApplicationContext context: Context): KnowledgeGraph = RoomKnowledgeGraph.open(context)

    /** java.net.http (JdkHttpTransport) does not exist on Android; HttpURLConnection does. */
    @Provides
    @Singleton
    fun provideHttpTransport(): HttpTransport = HttpUrlConnectionTransport()

    @Provides
    @Singleton
    fun provideAgentStateMachine(): AgentStateMachine = AgentStateMachine()

    @Provides
    @Singleton
    fun provideSecurityPolicy(): SecurityPolicy =
        SecurityPolicy(grantedCategories = setOf(PermissionCategory.TERMINAL, PermissionCategory.NETWORK))

    /**
     * The gate every approval-requiring tool goes through. Voice approvals are off by default, in which case
     * this is exactly the on-screen dialog; when the user turns them on, voice can read the request aloud and
     * refuse it, but never approve it (see [VoiceApprovalPrompt]). The speech engines are created on first voice use
     * and the setting is re-read on every request, so toggling it takes effect immediately.
     */
    @Provides
    @Singleton
    fun provideApprovalPrompt(
        composePrompt: ComposeApprovalPrompt,
        auditLog: JsonFileAuditLog,
        @ApplicationContext context: Context,
    ): ApprovalPrompt {
        val store = SharedPreferencesVoiceSettingsStore(context)
        // Created on the first approval that actually uses voice, then reused (a TextToSpeech binding per approval would leak).
        val io by lazy { VoiceApprovalPrompt.engineIo(AndroidSpeechToText(context), AndroidTextToSpeech(context)) }
        return VoiceApprovalPrompt(
            screen = composePrompt,
            io = { io },
            auditLog = auditLog,
            settings = { store.load().approval },
        )
    }

    /**
     * The on-device audit trail the Logs screen reads. Append-only JSON Lines under app-private storage;
     * fails closed at its default capacity (10,000 events) — once full, further sensitive actions are
     * refused rather than run unaudited, and there is deliberately no way to clear it from the app.
     */
    @Provides
    @Singleton
    fun provideEngineModes(prefs: AdvancedPrefs): EngineModes = EngineModes(AdvancedModeStore(prefs))

    @Provides
    @Singleton
    fun provideAuditLog(@ApplicationContext context: Context): JsonFileAuditLog =
        JsonFileAuditLog(context.filesDir.toPath().resolve("audit").resolve("audit.jsonl"))

    @Provides
    @Singleton
    fun provideToolRunner(
        registry: ToolRegistry,
        stateMachine: AgentStateMachine,
        policy: SecurityPolicy,
        approvalPrompt: ApprovalPrompt,
        auditLog: JsonFileAuditLog,
    ): ToolRunner {
        val delegate = ToolExecutor(registry, stateMachine)
        return SecureToolExecutor(registry, delegate, stateMachine, SecurityPolicyEnforcer(policy), approvalPrompt, auditLog = auditLog)
    }
}
