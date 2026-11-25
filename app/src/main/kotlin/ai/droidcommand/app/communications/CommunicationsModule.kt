package ai.droidcommand.app.communications

import ai.droidcommand.agent.ToolRegistry
import ai.droidcommand.app.prefs.AdvancedPrefs
import ai.droidcommand.communications.ApprovalQueue
import ai.droidcommand.communications.ArchiveChannel
import ai.droidcommand.communications.ArchivePoller
import ai.droidcommand.communications.CommsAdmin
import ai.droidcommand.communications.CommsBackend
import ai.droidcommand.communications.CommsBroker
import ai.droidcommand.communications.CommunicationsAgentTools
import ai.droidcommand.communications.CompositeBackend
import ai.droidcommand.communications.ConversationOrchestrator
import ai.droidcommand.communications.DraftInbox
import ai.droidcommand.communications.DraftTurnEngine
import ai.droidcommand.communications.FileShareEngine
import ai.droidcommand.communications.GrantStore
import ai.droidcommand.communications.JsonFileApprovalStore
import ai.droidcommand.communications.JsonFileDraftStore
import ai.droidcommand.communications.JsonFileGrantStore
import ai.droidcommand.communications.JsonFileReceiptStore
import ai.droidcommand.communications.JsonFileShareStore
import ai.droidcommand.communications.KillSwitch
import ai.droidcommand.communications.MediaGateway
import ai.droidcommand.communications.ModelResult
import ai.droidcommand.communications.Owner
import ai.droidcommand.communications.ReceiptStore
import ai.droidcommand.communications.ReplyModel
import ai.droidcommand.communications.ReplyService
import ai.droidcommand.communications.ShareGatedBackend
import ai.droidcommand.communications.VictorSmsCommsBackend
import ai.droidcommand.fleet.messenger.FleetMessengerController
import ai.droidcommand.fleet.messenger.MutablePairedNodeStore
import ai.droidcommand.fleet.messenger.PackageIdentityResolver
import ai.droidcommand.fleet.messenger.PairedNodeGateway
import ai.droidcommand.llm.factory.ChatResult
import ai.droidcommand.llm.routing.PolicyInference
import ai.droidcommand.security.JsonFileAuditLog
import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.nio.file.Files
import java.util.concurrent.Executors
import javax.inject.Singleton

/**
 * Victorious Framework: the one place the communications broker is assembled in the app. State lives under
 * `files/communications` (grants, approvals, receipts, the emergency-stop flag).
 */
class CommsStores(val grants: GrantStore, val approvals: ApprovalQueue, val kill: KillSwitch, val receipts: ReceiptStore)

/**
 * Every reply and summary goes through [PolicyInference.ask], so the owner's inference policy (this device only / my PC
 * only / confirmed cloud fallback, `docs/INFERENCE_POLICY.md`) decides where private conversation text may be sent.
 */
class PolicyReplyModel(private val inference: PolicyInference) : ReplyModel {
    override fun generate(feature: String, prompt: String): ModelResult = when (val r = inference.ask(feature, prompt)) {
        is ChatResult.Reply -> ModelResult.Text(r.text, r.route?.summary()?.take(400))
        is ChatResult.Failure -> ModelResult.Failure(r.message)
    }
}

@Module
@InstallIn(SingletonComponent::class)
object CommunicationsModule {
    @Provides
    @Singleton
    fun provideCommsStores(@ApplicationContext context: Context): CommsStores {
        val dir = context.filesDir.toPath().resolve("communications")
        Files.createDirectories(dir)
        val killFile = dir.resolve("emergency-stop")
        return CommsStores(
            grants = JsonFileGrantStore(dir.resolve("grants.json")),
            approvals = ApprovalQueue(clock = System::currentTimeMillis, store = JsonFileApprovalStore(dir.resolve("approvals.json"))),
            kill = KillSwitch(
                persist = { on -> if (on) Files.write(killFile, byteArrayOf(1)) else Files.deleteIfExists(killFile) },
                initial = Files.exists(killFile),
            ),
            receipts = JsonFileReceiptStore(dir.resolve("receipts.json")),
        )
    }

    @Provides
    @Singleton
    fun provideReplyModel(policy: PolicyInference): ReplyModel = PolicyReplyModel(policy)

    /**
     * The only live connector is VictorSMS's read-only archive. VictorBots has none yet, so everything it owns reports
     * "not connected" in discovery and is refused; nothing is simulated.
     */
    @Provides
    @Singleton
    fun provideArchiveChannel(store: MutablePairedNodeStore, resolver: PackageIdentityResolver, gateway: PairedNodeGateway): ArchiveChannel {
        val controller = FleetMessengerController(store, resolver, gateway)
        return ControllerArchiveChannel { operation, params -> controller.callOwnerGrantedRead("victorsms", operation, params) }
    }

    /** Owner-controlled, per-conversation file sharing. Tied to the same emergency stop as everything else in this module. */
    @Provides
    @Singleton
    fun provideFileShareEngine(@ApplicationContext context: Context, stores: CommsStores): FileShareEngine {
        val dir = context.filesDir.toPath().resolve("communications")
        Files.createDirectories(dir)
        return FileShareEngine(JsonFileShareStore(dir.resolve("file-shares.json").toFile()), emergencyStop = { stores.kill.engaged })
    }

    @Provides
    @Singleton
    fun provideMediaGateway(@ApplicationContext context: Context): MediaGateway = ContentResolverMediaGateway(context)

    /**
     * Attachment sends pass through [ShareGatedBackend]. The gateway reads only what the owner picked with Android's pickers; File-Manager's bridge is still a stub. Until a conversation's contact
     * can be resolved, every attachment send is refused: the gate fails closed rather than guessing.
     */
    @Provides
    @Singleton
    fun provideCommsBackend(archive: ArchiveChannel, shares: FileShareEngine, gateway: MediaGateway): CommsBackend =
        ShareGatedBackend(CompositeBackend(mapOf(Owner.VICTORSMS to VictorSmsCommsBackend(archive))), shares, gateway)

    @Provides
    @Singleton
    fun provideCommsBroker(stores: CommsStores, backend: CommsBackend, model: ReplyModel, audit: JsonFileAuditLog): CommsBroker =
        CommsBroker(backend, ReplyService(model), stores.grants, stores.approvals, stores.receipts, stores.kill, audit)

    /**
     * The owner's default-off switch for droidcommand-AI's own agent. Constructing this registers nothing; the tools appear only
     * after [CommunicationsAgentTools.apply] runs with the switch on (at start-up and when the owner flips it).
     */
    @Provides
    @Singleton
    fun provideCommunicationsAgentTools(registry: ToolRegistry, broker: CommsBroker, advanced: AdvancedPrefs): CommunicationsAgentTools =
        CommunicationsAgentTools(registry, broker, advanced::communicationsAgentToolsEnabled, persist = advanced::setCommunicationsAgentToolsEnabled)

    @Provides
    @Singleton
    fun provideCommsAdmin(stores: CommsStores): CommsAdmin = CommsAdmin(stores.grants, stores.approvals, stores.kill)

    /**
     * Reply drafts for conversations you switch on. Two worker threads share one model; the orchestrator keeps each
     * conversation in order and bounded. Nothing it does sends: its delivery hook is empty by construction.
     */
    @Provides
    @Singleton
    fun provideDraftRuntime(@ApplicationContext context: Context, stores: CommsStores, backend: CommsBackend, model: ReplyModel, archive: ArchiveChannel): DraftRuntime {
        val dir = context.filesDir.toPath().resolve("communications")
        Files.createDirectories(dir)
        val inbox = DraftInbox(JsonFileDraftStore(dir.resolve("drafts.json").toFile()))
        val pool = Executors.newFixedThreadPool(2) { r -> Thread(r, "reply-drafts").apply { isDaemon = true } }
        val stopped = { stores.kill.engaged }
        val orchestrator = ConversationOrchestrator(pool, 1, DraftTurnEngine(ReplyService(model), backend, inbox, stopped), { _, _ -> })
        return DraftRuntime(inbox, ArchivePoller(archive, inbox, orchestrator, stopped), archive, orchestrator)
    }

    @Provides
    @Singleton
    fun provideDraftAutoCheck(scheduler: DraftScheduler): DraftAutoCheck = scheduler
}
