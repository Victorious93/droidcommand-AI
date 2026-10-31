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
import ai.droidcommand.app.deviceconnect.VaultDeviceConnectStore
import ai.droidcommand.app.fleet.AndroidMessengerChannel
import ai.droidcommand.app.fleet.AndroidPackageIdentityResolver
import ai.droidcommand.app.fleet.SharedPreferencesPairedNodeStore
import ai.droidcommand.app.mode.AdvancedModeStore
import ai.droidcommand.app.mode.EngineModes
import ai.droidcommand.app.prefs.AdvancedPrefs
import ai.droidcommand.app.security.createAndroidSecretsVault
import ai.droidcommand.app.shizuku.AndroidShizukuHost
import ai.droidcommand.app.shizuku.BoundPrivilegedShell
import ai.droidcommand.app.shizuku.embedded.EmbeddedShizukuPayload
import ai.droidcommand.app.shizuku.embedded.EmbeddedShizukuStarter
import ai.droidcommand.app.shizuku.embedded.ShizukuStartNotifier
import ai.droidcommand.app.shizuku.embedded.WirelessAdbClient
import ai.droidcommand.app.termux.AndroidPendingIntentRunCommandClient
import ai.droidcommand.app.theme.DataStoreThemePreferenceStore
import ai.droidcommand.config.EncryptedSecretsVault
import ai.droidcommand.config.ThemePreferenceStore
import ai.droidcommand.conversations.RoomConversationStore
import ai.droidcommand.deviceconnect.DeviceConnectService
import ai.droidcommand.fleet.messenger.ExternalNodeTransport
import ai.droidcommand.fleet.messenger.MessengerBridgeClient
import ai.droidcommand.fleet.messenger.MessengerChannel
import ai.droidcommand.fleet.messenger.MutablePairedNodeStore
import ai.droidcommand.fleet.messenger.NodeTrustVerifier
import ai.droidcommand.fleet.messenger.PackageIdentityResolver
import ai.droidcommand.fleet.messenger.PairedNodeGateway
import ai.droidcommand.fleet.messenger.PairedNodeStore
import ai.droidcommand.hackerai.HackerAiTool
import ai.droidcommand.knowledge.android.RoomKnowledgeGraph
import ai.droidcommand.llm.factory.CloudPromptClient
import ai.droidcommand.llm.factory.CloudProviderSpec
import ai.droidcommand.metasploit.MetasploitExecutor
import ai.droidcommand.metasploit.MetasploitTool
import ai.droidcommand.metasploit.TermuxMetasploitExecutor
import ai.droidcommand.n8n.N8nClient
import ai.droidcommand.n8n.N8nListWorkflowsTool
import ai.droidcommand.n8n.N8nRunWorkflowTool
import ai.droidcommand.n8n.N8nSecrets
import ai.droidcommand.pentestswarm.ApprovalPromptStepApprover
import ai.droidcommand.pentestswarm.KaliAuthorizedScanTool
import ai.droidcommand.pentestswarm.LocalKaliPlaybookRunner
import ai.droidcommand.remote.HttpTransport
import ai.droidcommand.remote.HttpUrlConnectionTransport
import ai.droidcommand.remotecontrol.InMemoryRemoteStatusTray
import ai.droidcommand.remotecontrol.JsonFileRemoteControlStore
import ai.droidcommand.remotecontrol.RemoteActionExecutor
import ai.droidcommand.remotecontrol.RemoteCommandDispatcher
import ai.droidcommand.remotecontrol.RemoteControlSettingsController
import ai.droidcommand.remotecontrol.RemoteControlStore
import ai.droidcommand.remotecontrol.RemoteControlTool
import ai.droidcommand.remotecontrol.RemoteStatusTray
import ai.droidcommand.remotecontrol.ReplayGuard
import ai.droidcommand.remotecontrol.StubRemoteActionExecutor
import ai.droidcommand.remotecontrol.UnifiedLocalBackupStore
import ai.droidcommand.root.NullRootExecutor
import ai.droidcommand.root.RootTool
import ai.droidcommand.security.ApprovalPrompt
import ai.droidcommand.security.DefaultActionRisks
import ai.droidcommand.security.FleetCommandTool
import ai.droidcommand.security.JsonFileAuditLog
import ai.droidcommand.security.SecureToolExecutor
import ai.droidcommand.security.SecurityPolicy
import ai.droidcommand.security.SecurityPolicyEnforcer
import ai.droidcommand.setoolkit.NullSetExecutor
import ai.droidcommand.setoolkit.SetTool
import ai.droidcommand.shizuku.PrivilegedShell
import ai.droidcommand.shizuku.ShizukuHost
import ai.droidcommand.shizuku.ShizukuManager
import ai.droidcommand.shizuku.ShizukuTool
import ai.droidcommand.termux.ClientBackedTermuxExecutor
import ai.droidcommand.termux.TermuxExecutor
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
 * [MetasploitTool] on Android is bound to [TermuxMetasploitExecutor], which
 * runs `msfconsole` inside VictorSuite Kali through [TermuxExecutor] /
 * `com.termux.RUN_COMMAND` + `RUN_COMMAND_PENDING_INTENT`. That is **not**
 * [ai.droidcommand.metasploit.ShellBackedMetasploitExecutor] (desktop
 * ProcessBuilder) and **not** the Go pentest-swarm companion. SET stays
 * [NullSetExecutor] (TermuxSetExecutor is a later task). A live msfconsole
 * round-trip has not been run in the environment that wired this.
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
    fun provideTermuxExecutor(@ApplicationContext context: Context): TermuxExecutor =
        ClientBackedTermuxExecutor(
            client = AndroidPendingIntentRunCommandClient(context),
            probeAvailable = {
                AndroidPendingIntentRunCommandClient.isVictorSuiteInstalled(context) &&
                    AndroidPendingIntentRunCommandClient.hasRunCommandPermission(context)
            },
        )

    @Provides
    @Singleton
    fun provideMetasploitExecutor(termux: TermuxExecutor): MetasploitExecutor = TermuxMetasploitExecutor(termux)

    @Provides
    @Singleton
    fun provideKaliPlaybookRunner(
        termux: TermuxExecutor,
        metasploit: MetasploitExecutor,
        approvalPrompt: ApprovalPrompt,
    ): LocalKaliPlaybookRunner = LocalKaliPlaybookRunner(
        termux = termux,
        metasploit = metasploit,
        stepApprover = ApprovalPromptStepApprover(approvalPrompt),
    )

    @Provides
    @Singleton
    fun provideToolRegistry(
        @ApplicationContext context: Context,
        vault: EncryptedSecretsVault,
        transport: HttpTransport,
        advanced: AdvancedPrefs,
        termux: TermuxExecutor,
        metasploit: MetasploitExecutor,
        kaliPlaybookRunner: LocalKaliPlaybookRunner,
        fleetCommandTool: FleetCommandTool,
        privilegedShell: PrivilegedShell,
        remoteControlStore: RemoteControlStore,
        remoteActionExecutor: RemoteActionExecutor,
    ): ToolRegistry = ToolRegistry().apply {
        register(AutomationShellTool(AllowlistShellExecutor { advanced.shellAllowlist() }))
        register(AiPromptTool(CloudPromptClient(vault, transport)) { spec -> chatModelFor(context, spec) })
        register(RootTool(NullRootExecutor()))
        register(TermuxTool(termux))
        ShizukuTool.all(privilegedShell).forEach { register(it) }
        register(MetasploitTool(metasploit))
        register(KaliAuthorizedScanTool(kaliPlaybookRunner))
        register(SetTool(NullSetExecutor()))
        // Always-fail stub: AIDL bind is not started in this module. Registering it keeps the
        // name visible to anything that asks the registry; Pilot as DEVICE_OWNER still hides
        // it because the tool is scoped to Initiator.REMOTE.
        register(HackerAiTool())
        // Fleet Orchestrator action commands. SENSITIVE and always confirmed; hidden from the Pilot and Forge planners.
        register(fleetCommandTool)
        // Remote Control skeleton. Names contain `remote`; destructive variants never YOLO-waive.
        // execute() is a stub — no live wipe / power-off / lock / RustDesk.
        RemoteControlTool.all(remoteActionExecutor, remoteControlStore).forEach { register(it) }
        val n8n = N8nClient(transport) { vault.getSecret(N8nSecrets.API_KEY_ID) }
        register(N8nListWorkflowsTool(n8n, advanced::n8nServerUrl, advanced::n8nAiToolsEnabled))
        register(N8nRunWorkflowTool(n8n, advanced::n8nServerUrl, advanced::n8nAiToolsEnabled))
    }

    @Provides
    @Singleton
    fun provideRemoteControlStore(@ApplicationContext context: Context): RemoteControlStore =
        JsonFileRemoteControlStore(context.filesDir.toPath().resolve("remote-control").resolve("config.json"))

    @Provides
    @Singleton
    fun provideReplayGuard(): ReplayGuard = ReplayGuard()

    @Provides
    @Singleton
    fun provideRemoteStatusTray(): RemoteStatusTray = InMemoryRemoteStatusTray()

    @Provides
    @Singleton
    fun provideUnifiedLocalBackupStore(@ApplicationContext context: Context): UnifiedLocalBackupStore =
        UnifiedLocalBackupStore(context.filesDir.toPath().resolve("remote-control").resolve("backup.json"))

    @Provides
    @Singleton
    fun provideRemoteActionExecutor(): RemoteActionExecutor = StubRemoteActionExecutor()

    @Provides
    @Singleton
    fun provideRemoteCommandDispatcher(
        store: RemoteControlStore,
        replayGuard: ReplayGuard,
        statusTray: RemoteStatusTray,
    ): RemoteCommandDispatcher = RemoteCommandDispatcher(store, replayGuard, statusTray)

    @Provides
    @Singleton
    fun provideRemoteControlSettingsController(
        store: RemoteControlStore,
        dispatcher: RemoteCommandDispatcher,
        statusTray: RemoteStatusTray,
        backupStore: UnifiedLocalBackupStore,
    ): RemoteControlSettingsController = RemoteControlSettingsController(store, dispatcher, statusTray, backupStore)

    /** The model chat uses for [spec], read from the same preference chat writes. Falls back to the provider's suggestion. */
    private fun chatModelFor(context: Context, spec: CloudProviderSpec): String =
        context.getSharedPreferences(CHAT_PREFS, Context.MODE_PRIVATE).getString("model.${spec.id}", null) ?: spec.defaultModel

    /** Phase 1: Keystore-backed secrets (API keys). UNBUILT — see security/AndroidSecretsVault.kt. */
    @Provides
    @Singleton
    fun provideSecretsVault(@ApplicationContext context: Context): EncryptedSecretsVault = createAndroidSecretsVault(context)

    @Provides
    @Singleton
    fun provideThemePreferenceStore(store: DataStoreThemePreferenceStore): ThemePreferenceStore = store

    @Provides
    @Singleton
    fun provideDeviceConnectService(vault: EncryptedSecretsVault): DeviceConnectService =
        DeviceConnectService(VaultDeviceConnectStore(vault))

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
    fun provideSecurityPolicy(): SecurityPolicy = SecurityPolicy(
        grantedCategories = setOf(
            PermissionCategory.TERMINAL,
            PermissionCategory.NETWORK,
            PermissionCategory.DEVICE_CONTROL,
            PermissionCategory.REMOTE_CONTROL,
        ),
        // READ_ONLY catalog only. YOLO stays off ([YoloMode.ENABLED_DEFAULT]); turning it on
        // auto-approves shell/root/fleet/Shizuku/non-destructive remote but never Kali/pentest
        // authorization and never destructive remote actions (WIPE/POWER_OFF/LOCK). See [YoloMode.WARNING].
        autoApprovalPolicy = DefaultActionRisks.policy(),
    )

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

    /**
     * EXTERNAL_NODE Messenger transport (`core-fleet-messenger`). The pairing store starts empty.
     * Tools → Fleet can pair a node (package + observed cert) and then run read-only
     * `*.capabilities.get` / `*.status` (and fire-and-forget `*.up` + poll). No tool is registered
     * against the gateway, so Pilot/Forge/Kali/pentest paths are unchanged. Write/control ops other
     * than documented `*.up` are refused by [ai.droidcommand.fleet.messenger.FleetMessengerController].
     * No AIDL, no DependencyGuard, no shared signing key. Not run on a device yet.
     */
    @Provides
    @Singleton
    fun provideMutablePairedNodeStore(@ApplicationContext context: Context): MutablePairedNodeStore =
        SharedPreferencesPairedNodeStore(context)

    @Provides
    @Singleton
    fun providePairedNodeStore(store: MutablePairedNodeStore): PairedNodeStore = store

    @Provides
    @Singleton
    fun providePackageIdentityResolver(@ApplicationContext context: Context): PackageIdentityResolver = AndroidPackageIdentityResolver(context)

    @Provides
    @Singleton
    fun provideMessengerChannel(@ApplicationContext context: Context): MessengerChannel = AndroidMessengerChannel(context)

    @Provides
    @Singleton
    fun provideExternalNodeTransport(resolver: PackageIdentityResolver, channel: MessengerChannel): ExternalNodeTransport =
        MessengerBridgeClient(NodeTrustVerifier(resolver), channel)

    @Provides
    @Singleton
    fun providePairedNodeGateway(store: PairedNodeStore, transport: ExternalNodeTransport): PairedNodeGateway =
        PairedNodeGateway(store, transport)

    @Provides
    @Singleton
    fun provideEmbeddedShizukuPayload(@ApplicationContext context: Context): EmbeddedShizukuPayload =
        EmbeddedShizukuPayload(context)

    @Provides
    @Singleton
    fun provideWirelessAdbClient(@ApplicationContext context: Context): WirelessAdbClient = WirelessAdbClient(context)

    @Provides
    @Singleton
    fun provideShizukuStartNotifier(@ApplicationContext context: Context): ShizukuStartNotifier =
        ShizukuStartNotifier(context)

    @Provides
    @Singleton
    fun provideEmbeddedShizukuStarter(
        @ApplicationContext context: Context,
        prefs: AdvancedPrefs,
        payload: EmbeddedShizukuPayload,
        wireless: WirelessAdbClient,
        manager: ShizukuManager,
    ): EmbeddedShizukuStarter = EmbeddedShizukuStarter(context, prefs, payload, wireless, manager)

    @Provides
    @Singleton
    fun provideShizukuHost(
        @ApplicationContext context: Context,
        payload: EmbeddedShizukuPayload,
    ): ShizukuHost = AndroidShizukuHost(context, payload)

    @Provides
    @Singleton
    fun provideShizukuManager(host: ShizukuHost): ShizukuManager = ShizukuManager(host)

    /**
     * Binds the Shizuku UserService lazily on the first *approved* privileged op.
     * Unprivileged until then; missing/not-running Shizuku never crashes the app.
     */
    @Provides
    @Singleton
    fun providePrivilegedShell(
        @ApplicationContext context: Context,
        manager: ShizukuManager,
    ): PrivilegedShell = BoundPrivilegedShell(context, manager)

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
