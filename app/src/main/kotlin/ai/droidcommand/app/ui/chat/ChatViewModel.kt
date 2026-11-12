package ai.droidcommand.app.ui.chat

import ai.droidcommand.agent.ConversationStore
import ai.droidcommand.agent.GraphRetriever
import ai.droidcommand.agent.KnowledgeGraph
import ai.droidcommand.agent.Role
import ai.droidcommand.agent.ToolRegistry
import ai.droidcommand.agent.ToolRunner
import ai.droidcommand.app.bots.HelperBot
import ai.droidcommand.app.bots.HelperBotHandoff
import ai.droidcommand.app.bots.HelperBotTools
import ai.droidcommand.app.bots.HelperBots
import ai.droidcommand.app.brain.BrainRuntime
import ai.droidcommand.app.documents.DocumentRuntime
import ai.droidcommand.app.documents.DownloadOutcome
import ai.droidcommand.app.documents.EmbedderDownloadSupervisor
import ai.droidcommand.app.localmodels.ImportOutcome
import ai.droidcommand.app.localmodels.ImportedModel
import ai.droidcommand.app.localmodels.LocalModelImporter
import ai.droidcommand.app.localmodels.LocalModelRuntime
import ai.droidcommand.app.mcp.McpToolsRuntime
import ai.droidcommand.app.prefs.AdvancedPrefs
import ai.droidcommand.app.prompts.PromptLibrary
import ai.droidcommand.app.skills.SkillFilesPrompt
import ai.droidcommand.app.ui.pchost.PcHostChoice
import ai.droidcommand.app.voice.VoiceRuntime
import ai.droidcommand.config.AssistantCapability
import ai.droidcommand.config.AssistantCapabilitySettingsStore
import ai.droidcommand.config.EncryptedSecretsVault
import ai.droidcommand.config.SecretState
import ai.droidcommand.deviceconnect.DeviceConnectService
import ai.droidcommand.llm.factory.AiSlashCommands
import ai.droidcommand.llm.factory.ChatResult
import ai.droidcommand.llm.factory.ChatSession
import ai.droidcommand.llm.factory.CloudProviderCatalog
import ai.droidcommand.llm.factory.CloudProviderSpec
import ai.droidcommand.llm.factory.MemoryResult
import ai.droidcommand.llm.factory.SavedChat
import ai.droidcommand.llm.factory.WebSearchCatalog
import ai.droidcommand.llm.factory.newChatId
import ai.droidcommand.llm.factory.savedChats
import ai.droidcommand.llm.local.GgufFileName
import ai.droidcommand.llm.local.ModelInstallResult
import ai.droidcommand.llm.local.RamFit
import ai.droidcommand.llm.local.RamFitVerdict
import ai.droidcommand.llm.pchost.PcHostEndpoint
import ai.droidcommand.llm.pchost.PcHostKind
import ai.droidcommand.llm.pchost.PcHostSettingsStore
import ai.droidcommand.llm.routing.InferencePolicy
import ai.droidcommand.llm.routing.InferenceSettings
import ai.droidcommand.llm.routing.InferenceSettingsStore
import ai.droidcommand.llm.routing.RouteResolver
import ai.droidcommand.llm.pchost.PcHostToolNotices
import ai.droidcommand.notes.NoteDraft
import ai.droidcommand.rag.AttachResult
import ai.droidcommand.rag.DocumentInfo
import ai.droidcommand.remote.HttpTransport
import ai.droidcommand.skills.mcp.McpBridgedTool
import ai.droidcommand.skills.mcp.McpSelections
import ai.droidcommand.templates.PromptTemplate
import ai.droidcommand.templates.Skill
import ai.droidcommand.voice.SpeakMode
import ai.droidcommand.voice.VoiceController
import ai.droidcommand.voice.VoiceState
import ai.droidcommand.voice.WakeWordController
import ai.droidcommand.voice.WakeWordSettings
import ai.droidcommand.voice.WakeWordState
import ai.droidcommand.voice.android.AndroidSpeechToText
import ai.droidcommand.voice.android.AndroidTextToSpeech
import ai.droidcommand.voice.android.SharedPreferencesVoiceSettingsStore
import ai.droidcommand.voice.neural.WakeWordHost
import ai.droidcommand.voice.neural.WakeWordService
import ai.droidcommand.voice.selectTts
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.system.Os
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

data class ChatLine(val fromUser: Boolean, val text: String)

data class ChatUiState(
    val provider: CloudProviderSpec = CloudProviderCatalog.all.first(),
    val model: String = CloudProviderCatalog.all.first().defaultModel,
    val lines: List<ChatLine> = emptyList(),
    /** Reply text received so far for the in-flight turn; empty when idle. */
    val streaming: String = "",
    val sending: Boolean = false,
    val error: String? = null,
    val memoryEnabled: Boolean = false,
    val autoRemember: Boolean = false,
    val memoryStatus: String? = null,
    /** Kai Brain gate, read when Chat opens. Off means no note retrieve and no save. */
    val brainEnabled: Boolean = false,
    val brainStatus: String? = null,
    val pendingBrainSave: NoteDraft? = null,
    /** A spoken request that sounded destructive; Send waits on a Yes/No confirm dialog. */
    val pendingRiskySend: String? = null,
    val pendingRiskyReason: String? = null,
    /** Chat-toolbar toggle; off by default because turning it on sends the message text to the search provider. */
    val webSearchEnabled: Boolean = false,
    /** Non-fatal note from the last turn (e.g. web search failed and the reply is ungrounded). */
    val notice: String? = null,
    val speakMode: SpeakMode = SpeakMode.OFF,
    val voice: VoiceState = VoiceState(),
    val wakeWord: WakeWordState = WakeWordState(),
    /** The screen should show the POST_NOTIFICATIONS prompt now, then call [ChatViewModel.onNotificationPermissionResult]. */
    val askNotificationPermission: Boolean = false,
    val micAvailable: Boolean = true,
    /** Dictated text waiting for the screen to append to the input field; cleared by [ChatViewModel.consumePendingInput]. */
    val pendingInput: String? = null,
    /** Documents attached for Q&A. Their relevant passages are added to every message while attached. */
    val documents: List<DocumentInfo> = emptyList(),
    /** False while no on-device embedding model is installed: attaching is disabled and says why. */
    val documentsAvailable: Boolean = false,
    val attachingDocument: Boolean = false,
    /** Bytes of the embedding model downloaded so far, or null when no download is running. */
    val embedderDownloadBytes: Long? = null,
    val embedderDownloadMegabytes: Long = 0,
    /** True while the user is being asked to confirm downloading the embedding model over a metered (mobile) connection. */
    val confirmMeteredDownload: Boolean = false,
    /** Outcome of the last attach/remove, shown under the toolbar. */
    val documentStatus: String? = null,
    /** Chat-toolbar controls left visible by the Advanced settings. */
    val toolbar: Set<ToolbarToggle> = ToolbarLayout.defaults,
    /** On-device models the owner imported. Empty until one is imported. */
    val localModels: List<ImportedModel> = emptyList(),
    /** The on-device model turns run on, or null when the cloud provider above is in use. */
    val activeLocalModelId: String? = null,
    val localImporting: Boolean = false,
    /** Outcome of the last import, selection or deletion, shown in the options panel. */
    val localStatus: String? = null,
    /** True while the delete-model confirmation is open. */
    val confirmDeleteLocalModel: Boolean = false,
    /** Model servers saved in Settings, PC-hosted models. */
    val pcHosts: Map<PcHostKind, PcHostEndpoint> = emptyMap(),
    /** The PC server turns are sent to, or null. When set, [activeLocalModelId] is null. */
    val activePcHost: PcHostKind? = null,
    /** The owner's inference policy. [InferencePolicy.MANUAL] is the behaviour from before policies existed. */
    val inferencePolicy: InferencePolicy = InferencePolicy.MANUAL,
    /** True once the owner said yes to cloud fallback; without it that policy never reaches a cloud provider. */
    val cloudFallbackConfirmed: Boolean = false,
    /** True while the owner is being asked to confirm cloud fallback. */
    val confirmCloudFallback: Boolean = false,
    /** Where the last routed reply came from (endpoint, model, fallback reason, request size), or why nothing answered. */
    val routeSummary: String? = null,
    /**
     * While a helper bot runs on a PC-hosted or on-device model: whether its tools are offered to that model, and why
     * not when they are not. Null with no bot, and on a cloud provider (tools are always offered there).
     */
    val botToolStatus: String? = null,
    val conversationId: String = ChatSession.DEFAULT_CONVERSATION_ID,
    val savedChats: List<SavedChat> = emptyList(),
    val templates: List<PromptTemplate> = emptyList(),
    val skills: List<Skill> = emptyList(),
    /** Skill whose system text is added to each later request. Not written into the saved transcript. */
    val skillId: String? = null,
    val promptError: String? = null,
    /**
     * True when the selected cloud provider has a vault entry. Local-model turns do not
     * need this. Refreshed when Chat is shown again so a key saved in Settings is picked up.
     */
    val cloudKeyReady: Boolean = false,
    /** Helper bot from the Bot maker whose instructions are used instead of the skill while it runs. */
    val activeBotName: String? = null,
    val activeBotPrompt: String? = null,
)

/**
 * Compiles; never run on a device. All chat logic lives in the JVM-tested
 * [ai.droidcommand.llm.factory.ChatSession]; this class only owns UI state and threading. The
 * provider/model choice (not secret) is remembered in plain SharedPreferences; API keys are not.
 *
 * Knowledge-graph phases K2/K3: the Memory toggle (persisted, default off) is read once at
 * construction and decides whether [session] reads from and writes to [graph] for its whole
 * lifetime — it takes effect on the next screen visit, not live, the same restraint
 * [MemoryViewModel][ai.droidcommand.app.ui.memory.MemoryViewModel] does not need since it has no
 * per-request behavior to reconfigure.
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val vault: EncryptedSecretsVault,
    private val store: ConversationStore,
    transport: HttpTransport,
    graph: KnowledgeGraph,
    private val prompts: PromptLibrary,
    private val runtime: VoiceRuntime,
    private val documentRuntime: DocumentRuntime,
    private val advanced: AdvancedPrefs,
    private val onDeviceModels: LocalModelRuntime,
    private val deviceConnect: DeviceConnectService,
    private val brainRuntime: BrainRuntime,
    private val capabilities: AssistantCapabilitySettingsStore,
    private val toolRunner: ToolRunner,
    private val toolRegistry: ToolRegistry,
    private val mcpTools: McpToolsRuntime,
    private val pcHostStore: PcHostSettingsStore,
    private val inferenceSettings: InferenceSettingsStore,
    private val routeResolver: RouteResolver,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val prefs = context.getSharedPreferences("chat_prefs", Context.MODE_PRIVATE)
    private val memoryEnabled = prefs.getBoolean(KEY_MEMORY, false)
    private val autoRemember = memoryEnabled && prefs.getBoolean(KEY_AUTO_REMEMBER, false)
    private val brainEnabled = brainRuntime.enabled()
    private val session = ChatSession(
        vault,
        store,
        transport = transport,
        knowledge = if (memoryEnabled) GraphRetriever(graph) else null,
        knowledgeGraph = if (memoryEnabled) graph else null,
        webSearch = WebSearchCatalog.clientFor(vault, transport),
        documents = documentRuntime.retriever,
        local = onDeviceModels.resources,
        autoRememberEveryTurns = if (autoRemember) AUTO_REMEMBER_TURNS else 0,
        brain = if (brainEnabled) { query -> brainRuntime.librarian().retrieveContext(query) } else null,
        // The owner's inference policy: enforced inside the session on every send, search and remember.
        router = routeResolver,
    )

    private val tts = AndroidTextToSpeech(context)
    private val voiceStore = SharedPreferencesVoiceSettingsStore(context)

    // Saved settings were already normalized against the installed models when they were saved. The neural engine
    // reports itself unavailable until its model is installed and verified, and selectTts wraps it in a fallback
    // to the system voice, so a missing model never leaves replies silent.
    private val voice = VoiceController(
        AndroidSpeechToText(context),
        selectTts(voiceStore.load().ttsEngine, runtime.neuralTts(), tts),
    ) { v ->
        mutableState.update { it.copy(voice = v) }
        wake?.onVoiceState(v)
    }

    private fun micPermitted() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private var wake: WakeWordController? = null
    private var sendJob: Job? = null

    /** Set by [stopSending] for the turn in flight; the on-device generation polls it after every token. */
    @Volatile private var stopRequested = AtomicBoolean(false)

    /** The text of the turn in flight, given back to the input box when it is stopped. */
    @Volatile private var inFlightText: String? = null

    @Volatile private var importCancelled = AtomicBoolean(false)

    /** The running helper bot, kept so its MCP tools can be loaded once a cloud provider is chosen. */
    private var activeBot: HelperBot? = null

    @Volatile private var handsFreeRound = false

    /** Chat is on screen and may run the wake-word listener if the user setting is on. */
    @Volatile private var wakeWanted = false

    /** Wake-word settings held while the POST_NOTIFICATIONS prompt is on screen. */
    @Volatile private var awaitingNotificationAnswer: WakeWordSettings? = null

    private val mutableState = MutableStateFlow(initialState())
    val state: StateFlow<ChatUiState> = mutableState.asStateFlow()

    private fun initialState(): ChatUiState {
        val provider = CloudProviderCatalog.byId(prefs.getString(KEY_PROVIDER, null).orEmpty()) ?: CloudProviderCatalog.all.first()
        val model = prefs.getString(modelKey(provider), null) ?: provider.defaultModel
        return ChatUiState(
            provider = provider,
            model = model,
            memoryEnabled = memoryEnabled,
            brainEnabled = brainEnabled,
            autoRemember = prefs.getBoolean(KEY_AUTO_REMEMBER, false),
            webSearchEnabled = prefs.getBoolean(KEY_WEB_SEARCH, false),
            speakMode = speakModeFromPrefs(),
            micAvailable = voice.sttAvailable,
            toolbar = advanced.toolbarToggles(),
            activeLocalModelId = prefs.getString(KEY_LOCAL_MODEL, null),
            activePcHost = PcHostKind.byId(prefs.getString(PcHostChoice.KEY_PC_HOST, null)),
            cloudKeyReady = keyReady(provider),
        )
    }

    /** Reads the saved policy. Runs off the main thread (it is a file). */
    private fun refreshInferencePolicy() {
        val saved = routeResolver.settings()
        mutableState.update { it.copy(inferencePolicy = saved.policy, cloudFallbackConfirmed = saved.cloudFallbackConfirmed) }
    }

    /**
     * Chooses where replies may come from. Cloud fallback is not saved here: it opens a confirmation first
     * ([confirmCloudFallback]), because it lets the conversation leave the owner's own devices.
     */
    fun setInferencePolicy(policy: InferencePolicy) {
        if (policy == InferencePolicy.CLOUD_FALLBACK && !mutableState.value.cloudFallbackConfirmed) {
            mutableState.update { it.copy(confirmCloudFallback = true) }
            return
        }
        saveInferencePolicy(policy, mutableState.value.cloudFallbackConfirmed && policy == InferencePolicy.CLOUD_FALLBACK)
    }

    fun confirmCloudFallback() = saveInferencePolicy(InferencePolicy.CLOUD_FALLBACK, confirmed = true)

    fun dismissCloudFallback() = mutableState.update { it.copy(confirmCloudFallback = false) }

    private fun saveInferencePolicy(policy: InferencePolicy, confirmed: Boolean) {
        // Choosing any other policy withdraws the cloud confirmation, so it has to be given again next time.
        mutableState.update { it.copy(inferencePolicy = policy, cloudFallbackConfirmed = confirmed, confirmCloudFallback = false, routeSummary = null, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val current = routeResolver.settings()
            val saved = runCatching {
                inferenceSettings.save(
                    current.copy(
                        policy = policy,
                        cloudFallbackConfirmed = confirmed,
                        deviceModelId = current.deviceModelId ?: prefs.getString(KEY_LOCAL_MODEL, null)?.takeIf { it.isNotBlank() },
                        pcKind = current.pcKind ?: PcHostKind.byId(prefs.getString(PcHostChoice.KEY_PC_HOST, null)),
                    ),
                )
            }
            if (saved.isFailure) {
                // What is in force is what is on disk, so show that again rather than a choice that was not saved.
                refreshInferencePolicy()
                mutableState.update { it.copy(error = "The policy could not be saved, so it was not changed.") }
            }
        }
    }

    /** Remembers the on-device model / PC server a policy routes to, next to Chat's own single pick. */
    private fun rememberRouteChoice(deviceModelId: String? = null, pcKind: PcHostKind? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val current = routeResolver.settings()
                inferenceSettings.save(current.copy(deviceModelId = deviceModelId ?: current.deviceModelId, pcKind = pcKind ?: current.pcKind))
            }
        }
    }

    init {
        viewModelScope.launch {
            while (true) {
                delay(1_000)
                val notes = deviceConnect.inbox().drain()
                if (notes.isNotEmpty()) {
                    mutableState.update { state ->
                        state.copy(
                            lines = state.lines + notes.map { ChatLine(fromUser = false, text = "From ${it.fromName}: ${it.text}") },
                            notice = "A paired device sent a note.",
                        )
                    }
                }
            }
        }
        // Imported models are read from disk: load them off the main thread.
        viewModelScope.launch(Dispatchers.IO) { refreshLocalModels() }
        // The document index is a Room database: open and read it off the main thread.
        viewModelScope.launch(Dispatchers.IO) {
            val docs = runCatching { documentRuntime.attacher.list() }.getOrDefault(emptyList())
            mutableState.update {
                it.copy(
                    documents = docs,
                    documentsAvailable = documentRuntime.embedderAvailable(),
                    embedderDownloadMegabytes = documentRuntime.embedderDownloadMegabytes,
                    documentStatus = documentRuntime.embedderProblem,
                )
            }
        }
        // History, the chat list, and the template library come from Room, which refuses main-thread access.
        viewModelScope.launch(Dispatchers.IO) {
            val savedId = prefs.getString(KEY_CONVERSATION, null)?.takeIf { it.isNotBlank() } ?: ChatSession.DEFAULT_CONVERSATION_ID
            runCatching { session.open(savedId) }
            val lines = runCatching { session.history() }.getOrDefault(emptyList()).map { ChatLine(it.role == Role.USER, it.content) }
            val templates = runCatching { prompts.templates() }.getOrDefault(emptyList())
            val skills = runCatching { prompts.skills() }.getOrDefault(emptyList())
            val skillId = prefs.getString(KEY_SKILL, null)?.takeIf { id -> skills.any { it.id == id } }
            applyChatSystemPrompt(mutableState.value.copy(skills = skills, skillId = skillId))
            mutableState.update {
                it.copy(
                    lines = if (it.lines.isEmpty()) lines else it.lines,
                    savedChats = runCatching { savedChats(store) }.getOrDefault(emptyList()),
                    templates = templates,
                    skills = skills,
                    skillId = skillId,
                    conversationId = session.currentId(),
                )
            }
        }
    }

    /**
     * Re-reads the on-device choice and the installed models. The Models screen writes the choice while this view model
     * stays alive behind it, so without this a model selected there was not used here until the app restarted.
     *
     * A saved choice whose file is gone is kept, not replaced by the cloud provider: [send] then refuses with
     * [ChatRoute.MISSING_MESSAGE] instead of sending the chat off the device.
     */
    private fun refreshLocalModels() {
        refreshInferencePolicy()
        val models = onDeviceModels.list()
        val saved = prefs.getString(KEY_LOCAL_MODEL, null)?.takeIf { it.isNotBlank() }
        val missing = saved != null && models.none { it.id == saved }
        // The PC-hosted screen writes its choice and endpoints while this view model stays alive behind it.
        val pcHosts = PcHostKind.entries.mapNotNull { kind -> runCatching { pcHostStore.load(kind) }.getOrNull()?.let { kind to it } }.toMap()
        mutableState.update { state ->
            state.copy(
                pcHosts = pcHosts,
                activePcHost = PcHostKind.byId(prefs.getString(PcHostChoice.KEY_PC_HOST, null)),
                localModels = models,
                activeLocalModelId = saved,
                localStatus = if (missing) ChatRoute.MISSING_MESSAGE else state.localStatus?.takeIf { it != ChatRoute.MISSING_MESSAGE },
            )
        }
    }

    fun selectProvider(provider: CloudProviderSpec) {
        prefs.edit {
            putString(KEY_PROVIDER, provider.id)
            remove(KEY_LOCAL_MODEL)
            remove(PcHostChoice.KEY_PC_HOST)
        }
        mutableState.update {
            it.copy(
                provider = provider,
                model = prefs.getString(modelKey(provider), null) ?: provider.defaultModel,
                activeLocalModelId = null,
                activePcHost = null,
                error = null,
                localStatus = null,
                cloudKeyReady = keyReady(provider),
            )
        }
        // A helper bot's MCP tools were not loaded while an on-device model was selected (nothing is contacted then).
        activeBot?.let { loadMcpToolsFor(it) }
    }

    /** Chooses an imported on-device model for the following turns. The previous one is unloaded when the new one loads. */
    fun selectLocalModel(modelId: String) {
        prefs.edit {
            putString(KEY_LOCAL_MODEL, modelId)
            remove(PcHostChoice.KEY_PC_HOST)
        }
        mutableState.update { it.copy(activeLocalModelId = modelId, activePcHost = null, error = null, localStatus = null) }
        // Only under a policy: an install that never chose one keeps no policy file (see FileInferenceSettingsStore).
        if (mutableState.value.inferencePolicy.routed) rememberRouteChoice(deviceModelId = modelId)
        refreshBotToolStatus()
    }

    /**
     * Works out [ChatUiState.botToolStatus] for the model now selected. Off the main thread: it reads the PC-host
     * settings file or the model file's metadata. It loads no model and makes no network request.
     */
    private fun refreshBotToolStatus() {
        val state = mutableState.value
        if (state.activeBotName == null || (state.activePcHost == null && state.activeLocalModelId == null)) {
            mutableState.update { it.copy(botToolStatus = null) }
            return
        }
        viewModelScope.launch {
            val status = withContext(Dispatchers.IO) {
                val pc = state.activePcHost
                val localId = state.activeLocalModelId
                when {
                    pc != null -> runCatching { pcHostStore.load(pc) }.getOrNull()?.let { PcHostToolNotices.status(it.toolSupport, it.toolsForced) }
                    localId != null -> session.localToolSupport(localId)?.let { if (it.supported) "Tools: this on-device model can call them" else it.reason }
                    else -> null
                }
            }
            val now = mutableState.value
            if (now.activeBotName == state.activeBotName && now.activePcHost == state.activePcHost && now.activeLocalModelId == state.activeLocalModelId) {
                mutableState.update { it.copy(botToolStatus = status) }
            }
        }
    }

    /** Sends the following turns to the model server saved for [kind] in Settings, PC-hosted models. */
    fun selectPcHost(kind: PcHostKind) {
        prefs.edit {
            putString(PcHostChoice.KEY_PC_HOST, kind.id)
            remove(KEY_LOCAL_MODEL)
        }
        mutableState.update { it.copy(activePcHost = kind, activeLocalModelId = null, error = null, localStatus = null) }
        if (mutableState.value.inferencePolicy.routed) rememberRouteChoice(pcKind = kind)
        refreshBotToolStatus()
    }

    /**
     * Import button result. Copies the picked `.gguf` into app storage, records its SHA-256 and makes it the active
     * on-device model. Runs off the main thread: the copy and the digest read the whole file.
     */
    fun importLocalModel(uri: Uri) {
        if (mutableState.value.localImporting) return
        val cancel = AtomicBoolean(false)
        importCancelled = cancel
        mutableState.update { it.copy(localImporting = true, localStatus = "Importing…") }
        viewModelScope.launch(Dispatchers.IO) {
            var reportedBytes = 0L
            val outcome = runCatching {
                try {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: SecurityException) {
                    // One-shot grants still work for the copy below.
                }
                val name = GgufFileName.resolve(displayName(uri), uri.lastPathSegment, uri.toString())
                    ?: displayName(uri)
                val size = sizeOf(uri)
                val stream = context.contentResolver.openInputStream(uri) ?: error("The file could not be opened.")
                val copied = stream.use {
                    onDeviceModels.importModel(name, it, size, cancelled = cancel::get) { bytes ->
                        if (bytes - reportedBytes >= PROGRESS_STEP_BYTES) {
                            reportedBytes = bytes
                            mutableState.update { s -> s.copy(localStatus = "Importing… ${bytes / BYTES_PER_MEGABYTE} MB copied") }
                        }
                    }
                }
                if (copied is ImportOutcome.Failed && copied.reason.contains(LocalModelImporter.NOT_ENOUGH_STORAGE)) {
                    val path = runCatching {
                        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                            Os.readlink("/proc/self/fd/${pfd.fd}").takeIf { it.startsWith("/") && File(it).canRead() }
                        }
                    }.getOrNull()
                    if (path != null) onDeviceModels.importExisting(File(path)) else copied
                } else {
                    copied
                }
            }.getOrElse { ImportOutcome.Failed("Import failed: ${it.message ?: it.javaClass.simpleName}.") }
            val models = onDeviceModels.list()
            when (outcome) {
                is ImportOutcome.Imported -> selectImported(
                    outcome.model.id,
                    models,
                    "Imported ${outcome.model.label}. Chat now runs on this device." +
                        ramSuffix(outcome.model.metadata.sizeBytes, outcome.model.metadata.contextTokens) +
                        outcome.note?.let { " $it" }.orEmpty(),
                )
                is ImportOutcome.Duplicate -> selectImported(outcome.id, models, "That file is already imported. Selected it.")
                is ImportOutcome.Rejected -> mutableState.update { it.copy(localImporting = false, localStatus = outcome.reason) }
                is ImportOutcome.Failed -> mutableState.update { it.copy(localImporting = false, localStatus = outcome.reason) }
            }
        }
    }

    /** Stops a running import. The partial copy is deleted; nothing is registered. */
    fun cancelLocalImport() = importCancelled.set(true)

    private fun selectImported(id: String, models: List<ImportedModel>, status: String) {
        prefs.edit {
            putString(KEY_LOCAL_MODEL, id)
            remove(PcHostChoice.KEY_PC_HOST)
        }
        mutableState.update { it.copy(localImporting = false, localModels = models, activeLocalModelId = id, activePcHost = null, localStatus = status) }
    }

    fun askDeleteLocalModel() = mutableState.update { it.copy(confirmDeleteLocalModel = true) }

    fun dismissDeleteLocalModel() = mutableState.update { it.copy(confirmDeleteLocalModel = false) }

    /** Deletes the active model's file. The model is unloaded first, so nothing keeps the file open. */
    fun confirmDeleteLocalModel() {
        val id = mutableState.value.activeLocalModelId ?: return
        mutableState.update { it.copy(confirmDeleteLocalModel = false, localStatus = "Deleting…") }
        viewModelScope.launch(Dispatchers.IO) {
            onDeviceModels.remove(id) // unloads the model first
            prefs.edit { remove(KEY_LOCAL_MODEL) }
            mutableState.update {
                it.copy(localModels = onDeviceModels.list(), activeLocalModelId = null, localStatus = "Model file deleted. Chat uses the cloud provider again.")
            }
        }
    }

    fun setModel(model: String) {
        prefs.edit { putString(modelKey(mutableState.value.provider), model) }
        mutableState.update { it.copy(model = model) }
    }

    /** Combines enabled Skill files (with saved parameter values) and the active bot or Chat prompt. */
    private fun applyChatSystemPrompt(state: ChatUiState) {
        val files = if (capabilities.load().isEnabled(AssistantCapability.SKILL_SCRIPTS)) {
            runCatching { SkillFilesPrompt.assemble(context.filesDir.toPath()) }.getOrDefault(
                ai.droidcommand.skills.BoundSkillPrompt(null),
            )
        } else {
            ai.droidcommand.skills.BoundSkillPrompt(null)
        }
        val selected = state.activeBotPrompt ?: state.skills.firstOrNull { it.id == state.skillId }?.systemPrompt
        val prompt = listOfNotNull(files.prompt, selected).joinToString("\n\n").ifBlank { null }
        session.setRequestPrompt(prompt)
        files.skippedNotice()?.let { note ->
            mutableState.update { it.copy(notice = listOfNotNull(it.notice, note).joinToString(" ")) }
        }
    }

    fun send(text: String) {
        val current = mutableState.value
        if (current.sending || text.isBlank()) return
        // A slash command sends this one message to the named cloud provider and overrides any on-device model.
        val command = AiSlashCommands.parse(text)
        if (command is AiSlashCommands.Parsed.MissingPrompt) {
            // The input box was already cleared; give the command back so only the question has to be added.
            mutableState.update { it.copy(error = "Type a question after /${command.command}.", pendingInput = text) }
            return
        }
        val slash = command as? AiSlashCommands.Parsed.Command
        val slashProvider = slash?.let { CloudProviderCatalog.byId(it.providerId) }
        mutableState.update { it.copy(sending = true, streaming = "", error = null, notice = null, routeSummary = null) }
        sendJob?.cancel()
        val stop = AtomicBoolean(false)
        stopRequested = stop
        inFlightText = text
        sendJob = viewModelScope.launch(Dispatchers.IO) {
            applyChatSystemPrompt(current)
            // list() also registers the models with the repository before the first on-device turn.
            val route = ChatRoute.decide(
                selectedLocalId = current.activeLocalModelId,
                installedIds = onDeviceModels.list().map { it.id }.toSet(),
                slashCommandNamesCloud = slashProvider != null,
                pcHost = current.activePcHost,
                // Read fresh: the address or model may have been changed in Settings since this screen last refreshed.
                pcHostEndpoint = current.activePcHost?.let { kind -> runCatching { pcHostStore.load(kind) }.getOrNull() },
            )
            val outgoing = slash?.prompt ?: text
            // Read fresh: the policy may have been changed since this screen last refreshed.
            val policy = routeResolver.policy()
            val result = if (policy.routed && slashProvider == null) {
                // The owner's policy decides where this goes. A place that fails is followed by the next allowed one;
                // text already shown from the failed place is cleared first.
                runCatching {
                    session.sendRouted(
                        outgoing,
                        useWebSearch = current.webSearchEnabled,
                        cancelled = stop::get,
                        onRestart = { mutableState.update { it.copy(streaming = "") } },
                    ) { delta ->
                        if (!stop.get()) mutableState.update { it.copy(streaming = it.streaming + delta) }
                    }
                }.getOrElse { ChatResult.Failure(it.message ?: "The message could not be sent.") }
            } else if (route is ChatRoute.OnDeviceMissing && !policy.routed) {
                ChatResult.Failure(route.message)
            } else if (route is ChatRoute.PcHostNotReady && !policy.routed) {
                ChatResult.Failure(route.message)
            } else if (route is ChatRoute.PcHosted && !policy.routed) {
                // The message goes to the PC. Stop closes the connection, so the PC stops generating too.
                runCatching {
                    session.sendPcHost(route.target, outgoing, useWebSearch = current.webSearchEnabled, cancelled = stop::get) { delta ->
                        if (!stop.get()) mutableState.update { it.copy(streaming = it.streaming + delta) }
                    }
                }.getOrElse { ChatResult.Failure("PC-hosted model: the request could not be made (${it.message ?: it.javaClass.simpleName}).") }
            } else if (route is ChatRoute.OnDevice && !policy.routed) {
                // An on-device turn makes no network request: ChatSession skips web search and tools for it and says so.
                runCatching {
                    session.sendLocal(route.modelId, outgoing, useWebSearch = current.webSearchEnabled, cancelled = stop::get) { delta ->
                        if (!stop.get()) mutableState.update { it.copy(streaming = it.streaming + delta) }
                    }
                }.getOrElse { ChatResult.Failure(it.message ?: "The on-device model could not run this message.") }
            } else {
                val provider = slashProvider ?: current.provider
                val model = if (slashProvider == null) {
                    current.model
                } else {
                    prefs.getString(modelKey(slashProvider), null) ?: slashProvider.defaultModel
                }
                session.send(provider, model, outgoing, useWebSearch = current.webSearchEnabled) { delta ->
                    mutableState.update { it.copy(streaming = it.streaming + delta) }
                }
            }
            // Read the saved-chat list and speak the reply outside the update: the flow may run the lambda more than once
            // (speaking re-enters this flow through the voice callback, which can force exactly such a retry and a second speak).
            val refreshedChats = if (result is ChatResult.Reply) runCatching { savedChats(store) }.getOrNull() else null
            val cancelled = !isActive || stop.get()
            if (!cancelled) {
                val routeSummary = when (result) {
                    is ChatResult.Reply -> result.route?.summary()
                    is ChatResult.Failure -> result.route?.summary()
                }
                mutableState.update { it.afterTurn(result, text, null, refreshedChats).copy(routeSummary = routeSummary) }
                if (result is ChatResult.Reply) {
                    if (handsFreeRound) {
                        voice.speak(result.text)
                    } else {
                        voice.onReply(result.text, mutableState.value.speakMode)
                    }
                }
            } else if (stopRequested === stop) {
                // Only while this is still the newest turn: a later send must not have its state reset by a stopped one.
                // A stopped turn that had already run a tool action says so: the owner must know before sending again.
                val ran = (result as? ChatResult.Failure)?.message?.let { ChatSession.TOOLS_RAN_NOTE.find(it)?.value }
                mutableState.update { it.copy(sending = false, streaming = "", notice = listOfNotNull("Stopped.", ran).joinToString(" ")) }
            }
            if (stopRequested === stop) {
                handsFreeRound = false
                sendJob = null
                inFlightText = null
            }
        }
    }

    /**
     * Stops the in-flight chat reply. The send button comes back and the message returns to the input box.
     *
     * An on-device reply really stops: the generation is told to end (also while it is still reading the prompt), its
     * partial text is discarded and nothing is saved. Without that the model kept generating in the background, the
     * next message waited behind it, and the "stopped" reply was written into the saved chat.
     */
    fun stopSending() {
        stopRequested.set(true)
        session.requestStopLocal()
        session.requestStopPcHost()
        sendJob?.cancel()
        sendJob = null
        handsFreeRound = false
        val text = inFlightText
        inFlightText = null
        mutableState.update { it.copy(sending = false, streaming = "", notice = "Stopped.", pendingInput = text ?: it.pendingInput) }
        voice.stopSpeaking()
    }

    /**
     * Attach button result: reads the picked file (bounded), extracts text (PDF or plain text/Markdown), embeds
     * and indexes it on this device. Nothing is sent anywhere by attaching; the relevant passages are sent to
     * the chat provider only as part of later messages. Runs off the main thread (embedding is slow).
     */
    fun attachDocument(uri: Uri) {
        if (mutableState.value.attachingDocument) return
        mutableState.update { it.copy(attachingDocument = true, documentStatus = "Reading document…") }
        viewModelScope.launch(Dispatchers.IO) {
            val status: String
            val result = runCatching {
                val name = displayName(uri)
                val stream = context.contentResolver.openInputStream(uri) ?: error("The file could not be opened.")
                documentRuntime.attacher.attach(name, stream)
            }
            val docs = runCatching { documentRuntime.attacher.list() }.getOrDefault(mutableState.value.documents)
            status = result.fold(
                onSuccess = { r ->
                    when (r) {
                        is AttachResult.Attached -> "Attached ${r.info.name} (${r.info.chunkCount} passages)."
                        is AttachResult.Failed -> r.message
                    }
                },
                onFailure = { "Could not attach the document: ${it.message ?: it.javaClass.simpleName}" },
            )
            mutableState.update { it.copy(attachingDocument = false, documents = docs, documentStatus = status) }
        }
    }

    @Volatile private var embedderDownloadCancelled = false

    private val networkLinks by lazy { NetworkLinkChecker(context) }

    /**
     * Download-button handler. On a metered connection it first asks for confirmation (the model is large);
     * [confirmMeteredEmbedderDownload] proceeds. An earlier interrupted download is resumed, not restarted.
     */
    fun downloadEmbedder() {
        if (mutableState.value.embedderDownloadBytes != null) return
        when (networkLinks.activeLink()) {
            NetworkLink.NONE -> mutableState.update { it.copy(documentStatus = "No network connection. Connect and try again; a partial download is kept and resumed.") }
            NetworkLink.METERED -> mutableState.update { it.copy(confirmMeteredDownload = true) }
            NetworkLink.UNMETERED -> startEmbedderDownload()
        }
    }

    fun confirmMeteredEmbedderDownload() {
        mutableState.update { it.copy(confirmMeteredDownload = false) }
        startEmbedderDownload(allowMetered = true)
    }

    fun dismissMeteredEmbedderDownload() {
        mutableState.update { it.copy(confirmMeteredDownload = false) }
    }

    private fun startEmbedderDownload(allowMetered: Boolean = false) {
        if (mutableState.value.embedderDownloadBytes != null) return
        embedderDownloadCancelled = false
        mutableState.update { it.copy(embedderDownloadBytes = 0, documentStatus = "Downloading the embedding model from huggingface.co…") }
        viewModelScope.launch(Dispatchers.IO) {
            val supervisor = EmbedderDownloadSupervisor(documentRuntime::installEmbedder, networkLinks::activeLink)
            val outcome = runCatching {
                supervisor.run(
                    allowMetered = allowMetered,
                    userCancelled = { embedderDownloadCancelled },
                    onProgress = { bytes -> mutableState.update { it.copy(embedderDownloadBytes = bytes) } },
                    onRetrying = { message -> mutableState.update { it.copy(documentStatus = message) } },
                )
            }
            val ready = runCatching { documentRuntime.embedderAvailable() }.getOrDefault(false)
            val result = (outcome.getOrNull() as? DownloadOutcome.Finished)?.result
            val paused = outcome.getOrNull() == DownloadOutcome.PausedForMetered && !ready
            val status = when {
                ready -> "Embedding model ready. You can attach documents."
                paused -> "Paused: the connection is metered. Confirm to continue from where it stopped."
                outcome.isFailure -> "Download failed: ${outcome.exceptionOrNull()?.message ?: "unknown error"}"
                result is ModelInstallResult.Failed -> "Download failed: ${result.reason}. Tap Download again to resume."
                result == null -> "Download cancelled. Tap Download to resume."
                else -> documentRuntime.embedderProblem ?: "The embedding model is installed but could not be loaded."
            }
            mutableState.update {
                it.copy(embedderDownloadBytes = null, documentsAvailable = ready, documentStatus = status, confirmMeteredDownload = paused)
            }
        }
    }

    fun cancelEmbedderDownload() {
        embedderDownloadCancelled = true
    }

    fun removeDocument(docId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val failure = runCatching { documentRuntime.attacher.remove(docId) }.exceptionOrNull()
            val docs = runCatching { documentRuntime.attacher.list() }.getOrDefault(mutableState.value.documents)
            mutableState.update { it.copy(documents = docs, documentStatus = failure?.let { f -> "Could not remove it: ${f.message}" }) }
        }
    }

    private fun displayName(uri: Uri): String =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "document"

    private fun sizeOf(uri: Uri): Long? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    }.getOrNull()

    private fun ramSuffix(sizeBytes: Long?, contextTokens: Int): String {
        val size = sizeBytes ?: return ""
        val snap = onDeviceModels.resources.memory?.snapshot() ?: return ""
        val fit = RamFit.assess(size, contextTokens, snap.totalBytes, snap.availableBytes)
        return if (fit.verdict == RamFitVerdict.TOO_BIG || fit.verdict == RamFitVerdict.TIGHT) " ${fit.reason}" else ""
    }

    /** Starts an empty conversation and keeps the previous one in the list. */
    fun newChat() {
        if (mutableState.value.sending) return
        viewModelScope.launch(Dispatchers.IO) {
            val id = newChatId()
            session.open(id)
            switchTo(id, emptyList())
        }
    }

    /** Puts expanded template text into the input field. Does not send it. */
    fun insertDraft(text: String) {
        mutableState.update { it.copy(pendingInput = text, promptError = null) }
    }

    fun openChat(id: String) {
        if (mutableState.value.sending) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { session.open(id) }.onFailure { error ->
                mutableState.update { it.copy(error = error.message) }
                return@launch
            }
            val lines = session.history().map { ChatLine(it.role == Role.USER, it.content) }
            switchTo(session.currentId(), lines)
        }
    }

    /** Deletes one saved chat. Deleting the open chat starts a new empty one. */
    fun deleteChat(id: String) {
        if (mutableState.value.sending) return
        viewModelScope.launch(Dispatchers.IO) {
            store.delete(id)
            if (session.currentId() == id) {
                session.open(newChatId())
                switchTo(session.currentId(), emptyList())
            } else {
                mutableState.update { it.copy(savedChats = savedChats(store)) }
            }
        }
    }

    fun selectSkill(id: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            val skill = id?.let { chosen -> mutableState.value.skills.firstOrNull { it.id == chosen } }
            prefs.edit { if (skill == null) remove(KEY_SKILL) else putString(KEY_SKILL, skill.id) }
            applyChatSystemPrompt(mutableState.value)
            mutableState.update { it.copy(skillId = skill?.id, promptError = null) }
        }
    }

    fun saveTemplate(name: String, body: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val saved = runCatching { prompts.saveTemplate(name, body) }
            mutableState.update {
                it.copy(
                    templates = runCatching { prompts.templates() }.getOrDefault(it.templates),
                    promptError = saved.exceptionOrNull()?.message,
                )
            }
        }
    }

    fun deleteTemplate(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            prompts.deleteTemplate(id)
            mutableState.update { it.copy(templates = prompts.templates()) }
        }
    }

    fun saveSkill(name: String, systemPrompt: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val saved = runCatching { prompts.saveSkill(name, systemPrompt) }
            mutableState.update {
                it.copy(
                    skills = runCatching { prompts.skills() }.getOrDefault(it.skills),
                    promptError = saved.exceptionOrNull()?.message,
                )
            }
        }
    }

    fun deleteSkill(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            prompts.deleteSkill(id)
            val still = prompts.skills()
            val selected = mutableState.value.skillId?.takeIf { chosen -> still.any { it.id == chosen } }
            applyChatSystemPrompt(mutableState.value.copy(skillId = selected))
            prefs.edit { if (selected == null) remove(KEY_SKILL) else putString(KEY_SKILL, selected) }
            mutableState.update { it.copy(skills = still, skillId = selected) }
        }
    }

    private fun switchTo(id: String, lines: List<ChatLine>) {
        prefs.edit { putString(KEY_CONVERSATION, id) }
        mutableState.update {
            it.copy(
                lines = lines,
                streaming = "",
                error = null,
                conversationId = id,
                savedChats = runCatching { savedChats(store) }.getOrDefault(it.savedChats),
            )
        }
    }

    /** Re-reads the Advanced toolbar choice; called when Chat is shown, since Settings can change it. */
    fun reloadToolbar() {
        mutableState.update {
            it.copy(toolbar = advanced.toolbarToggles(), cloudKeyReady = keyReady(it.provider))
        }
        // The Models screen may have selected, imported or removed a model while this screen was behind it.
        if (!mutableState.value.sending) viewModelScope.launch(Dispatchers.IO) { refreshLocalModels() }
    }

    /** Takes effect on the very next message (unlike Memory, it is a per-request flag, not session wiring). */
    fun setWebSearchEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_WEB_SEARCH, enabled) }
        mutableState.update { it.copy(webSearchEnabled = enabled) }
    }

    /** Persists the on/off choice for the next time this screen is opened; does not reconfigure [session] now. */
    fun setMemoryEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_MEMORY, enabled) }
        mutableState.update { it.copy(memoryEnabled = enabled) }
    }

    /** Persists the choice for the next time this screen is opened. Each run costs one extra request to the chat provider. */
    fun setAutoRemember(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_AUTO_REMEMBER, enabled) }
        mutableState.update { it.copy(autoRemember = enabled) }
    }

    fun askSaveChatToBrain() {
        if (!brainRuntime.enabled()) {
            mutableState.update { it.copy(brainStatus = "Turn Brain on in Advanced first.") }
            return
        }
        if (mutableState.value.sending) return
        viewModelScope.launch(Dispatchers.IO) {
            val turns = runCatching { session.history() }.getOrDefault(emptyList())
            if (turns.isEmpty()) {
                mutableState.update { it.copy(brainStatus = "Nothing to save yet.") }
                return@launch
            }
            val title = turns.firstOrNull { it.role == Role.USER }?.content?.take(40) ?: "Chat"
            val draft = brainRuntime.librarian().draftChat(
                title,
                turns.map { if (it.role == Role.USER) "User" to it.content else "Assistant" to it.content },
            )
            mutableState.update { it.copy(pendingBrainSave = draft, brainStatus = null) }
        }
    }

    fun askSaveFactToBrain(title: String, fact: String) {
        if (!brainRuntime.enabled()) {
            mutableState.update { it.copy(brainStatus = "Turn Brain on in Advanced first.") }
            return
        }
        if (fact.isBlank()) {
            mutableState.update { it.copy(brainStatus = "Type a fact first.") }
            return
        }
        val draft = brainRuntime.librarian().draftFact(title.ifBlank { "Fact" }, fact)
        mutableState.update { it.copy(pendingBrainSave = draft, brainStatus = null) }
    }

    fun confirmSaveToBrain() {
        val draft = mutableState.value.pendingBrainSave ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val path = runCatching { brainRuntime.librarian().commit(draft) }
                .getOrElse { err ->
                    mutableState.update {
                        it.copy(brainStatus = "Could not save: ${err.message ?: err.javaClass.simpleName}", pendingBrainSave = null)
                    }
                    return@launch
                }
            mutableState.update { it.copy(pendingBrainSave = null, brainStatus = "Saved $path") }
        }
    }

    fun dismissSaveToBrain() {
        mutableState.update { it.copy(pendingBrainSave = null) }
    }

    /** K3: extracts and saves the current conversation to the knowledge graph, using the last-used provider. */
    fun rememberChat() {
        if (mutableState.value.sending) return
        mutableState.update { it.copy(memoryStatus = "Remembering…") }
        viewModelScope.launch(Dispatchers.IO) {
            val status = when (val result = session.rememberConversation()) {
                is MemoryResult.Saved -> "Remembered ${result.entityCount} fact(s)."
                is MemoryResult.NothingToRemember -> "Nothing new to remember."
                is MemoryResult.Failure -> result.message
            }
            mutableState.update { it.copy(memoryStatus = status) }
        }
    }

    /** Mic button. [micPermitted] is the current RECORD_AUDIO grant; a denial shows a message and does nothing else. */
    fun toggleMic(micPermitted: Boolean) {
        if (mutableState.value.voice.listening) {
            voice.stopListening()
        } else {
            voice.startListening(micPermitted) { text -> mutableState.update { it.copy(pendingInput = text) } }
        }
    }

    fun consumePendingInput() = mutableState.update { it.copy(pendingInput = null) }

    fun cycleSpeakMode() {
        val next = SpeakMode.entries[(mutableState.value.speakMode.ordinal + 1) % SpeakMode.entries.size]
        prefs.edit { putString(KEY_SPEAK_MODE, next.name) }
        if (next == SpeakMode.OFF) voice.stopSpeaking()
        mutableState.update { it.copy(speakMode = next) }
    }

    /** Tap-to-speak (and manual replay in any mode): reads the latest assistant reply aloud, or stops if already speaking. */
    fun speakLastReply() {
        if (mutableState.value.voice.speaking) return voice.stopSpeaking()
        mutableState.value.lines.lastOrNull { !it.fromUser }?.let { voice.speak(it.text) }
    }

    fun dismissVoiceError() = voice.clearError()

    /** Voice assistant: one round of listening; [onTranscript] gets the recognised sentence. */
    fun listenOnce(micPermitted: Boolean, onTranscript: (String) -> Unit) {
        if (mutableState.value.voice.listening) return
        voice.startListening(micPermitted, onTranscript)
    }

    /** Voice assistant: reads [text] aloud with the user's chosen voice. */
    fun speakText(text: String) {
        if (text.isNotBlank()) voice.speak(text)
    }

    /** Voice assistant: stops listening and speaking at once. */
    fun stopVoice() {
        voice.stopListening()
        voice.stopSpeaking()
    }

    /**
     * Picks up a bot the user tapped "Run" on in the Bot maker. Its instructions replace the skill text for the
     * next messages. Tools not on the bot's allow-list are refused by [HelperBotTools]; allowed tools that can
     * change the phone still go through the normal approval prompt.
     */
    fun pickUpBot() {
        val bot = HelperBotHandoff.consume() ?: return
        if (bot.model.isNotBlank() && mutableState.value.activeLocalModelId == null && mutableState.value.activePcHost == null) setModel(bot.model)
        // Only the tools that are, right now, the ones the owner ticked. MCP tools join after they are loaded below.
        val allowed = mcpTools.resolve(bot.allowedTools, bot.mcpBindings).allowed
        session.setToolAccess(
            HelperBotTools.scopedRunner(toolRunner, allowed),
            HelperBotTools.offeredTools(toolRegistry, allowed),
        )
        mutableState.update {
            it.copy(activeBotName = bot.name, activeBotPrompt = HelperBots.systemPrompt(bot, allowed), notice = "Running helper bot \"${bot.name}\".")
        }
        applyChatSystemPrompt(mutableState.value)
        activeBot = bot
        refreshBotToolStatus()
        // With an on-device model selected the bot's MCP servers are not contacted: an on-device chat makes no
        // network request of its own. Its other tools are offered when the model can call them (see botToolStatus).
        if (mutableState.value.activeLocalModelId == null) loadMcpToolsFor(bot)
    }

    /**
     * A bot that lists MCP tools (`mcp_…`) needs them loaded from the owner's MCP servers first. This only runs
     * when MCP is turned on; the tools still go through the same allow-list and approval prompts as above.
     *
     * A saved MCP tool is allowed only while it is the very server entry and remote tool the owner ticked. A
     * server added later under the same label, or a name that is no longer clear, is not offered and not callable.
     */
    private fun loadMcpToolsFor(bot: HelperBot) {
        if (bot.allowedTools.none { McpBridgedTool.isMcpTool(it) }) return
        viewModelScope.launch {
            val now = withContext(Dispatchers.IO) {
                mcpTools.refreshIfNeeded()
                mcpTools.resolve(bot.allowedTools, bot.mcpBindings)
            }
            if (mutableState.value.activeBotName != bot.name) return@launch
            session.setToolAccess(
                HelperBotTools.scopedRunner(toolRunner, now.allowed),
                HelperBotTools.offeredTools(toolRegistry, now.allowed),
            )
            val words = when {
                now.needsReselect.isNotEmpty() -> McpSelections.reselectWords(now.needsReselect)?.replace("Tap Edit", "In Bot maker, tap Edit")
                now.unavailable.isEmpty() -> null
                !mcpTools.enabled() -> "Extra AI tools (MCP) are off, so this bot cannot use its MCP tools."
                else -> "Some MCP tools for this bot are not available right now: ${now.unavailable.sorted().joinToString(", ")}."
            }
            if (words != null) mutableState.update { it.copy(notice = words) }
            // The prompt names the bot's tools; say only the ones it can really use now.
            val prompt = HelperBots.systemPrompt(bot, now.allowed)
            if (prompt != mutableState.value.activeBotPrompt) {
                mutableState.update { it.copy(activeBotPrompt = prompt) }
                applyChatSystemPrompt(mutableState.value)
            }
        }
    }

    fun stopBot() {
        activeBot = null
        session.setToolAccess(null)
        mutableState.update { it.copy(activeBotName = null, activeBotPrompt = null, notice = null, botToolStatus = null) }
        applyChatSystemPrompt(mutableState.value)
    }

    fun confirmRiskySend() {
        val text = mutableState.value.pendingRiskySend ?: return
        mutableState.update { it.copy(pendingRiskySend = null, pendingRiskyReason = null, notice = null) }
        handsFreeRound = true
        send(text)
    }

    fun dismissRiskySend() {
        mutableState.update { it.copy(pendingRiskySend = null, pendingRiskyReason = null, notice = "Cancelled.") }
    }

    /**
     * Hands-free: after the wake word, the spoken sentence is sent (not left in the box),
     * the screen state for this turn is cleared, and the answer is spoken. Tool use still
     * goes through the existing confirmation dialog.
     */
    private fun onHandsFreeTranscript(spoken: String) {
        val command = ai.droidcommand.app.assistant.JarvisCommandRouter.route(spoken)
        when (command) {
            ai.droidcommand.app.assistant.JarvisCommand.Nothing -> Unit
            ai.droidcommand.app.assistant.JarvisCommand.Stop -> {
                stopVoice()
                stopSending()
            }
            is ai.droidcommand.app.assistant.JarvisCommand.Open -> {
                ai.droidcommand.app.assistant.JarvisNavInbox.offer(command.destination)
                mutableState.update { it.copy(notice = "Opening ${command.destination.name.lowercase()}…", pendingInput = "") }
            }
            is ai.droidcommand.app.assistant.JarvisCommand.SaveNote -> {
                askSaveFactToBrain(command.title, command.text)
            }
            is ai.droidcommand.app.assistant.JarvisCommand.NeedsConfirmation -> {
                mutableState.update {
                    it.copy(
                        pendingRiskySend = command.text,
                        pendingRiskyReason = command.reason,
                        pendingInput = "",
                        notice = command.reason,
                    )
                }
            }
            is ai.droidcommand.app.assistant.JarvisCommand.Ask -> {
                mutableState.update { it.copy(pendingInput = "", notice = null, error = null) }
                handsFreeRound = true
                send(command.text)
            }
        }
    }

    /**
     * Wake word is opt-in (off by default), needs its model installed and RECORD_AUDIO, and is tied to the
     * Chat screen being visible: leaving Chat stops the microphone and its notification; returning to Chat
     * starts it again if the setting is still on. After a wake, [onHandsFreeTranscript] sends the request
     * and speaks the answer; tools still need confirmation.
     */
    fun startWakeWordIfEnabled() {
        wakeWanted = true
        viewModelScope.launch(Dispatchers.IO) {
            val features = runtime.refresh() // hashes the model files: must not run on the main thread
            val settings = voiceStore.load().normalized(features)
            if (!wakeWanted || !settings.wakeWord.enabled || !features.wakeWord) return@launch
            if (shouldAskNotificationPermission(Build.VERSION.SDK_INT, notificationsPermitted())) {
                // Ask first so the "microphone is on" notification can show; the screen answers via onNotificationPermissionResult.
                awaitingNotificationAnswer = settings.wakeWord
                mutableState.update { it.copy(askNotificationPermission = true) }
            } else {
                startWakeWord(settings.wakeWord)
            }
        }
    }

    /** Stops wake-word listening because Chat is no longer on screen. Does not change the user's setting. */
    fun stopWakeWord() {
        wakeWanted = false
        awaitingNotificationAnswer = null
        tearDownWakeWord()
        voice.stopListening()
        voice.stopSpeaking()
        mutableState.update { it.copy(askNotificationPermission = false, wakeWord = it.wakeWord.copy(listeningForWakeWord = false)) }
    }

    /** The screen's answer to the POST_NOTIFICATIONS prompt. Wake word starts either way; a denial is stated, not hidden. */
    fun onNotificationPermissionResult(granted: Boolean) {
        val settings = awaitingNotificationAnswer
        awaitingNotificationAnswer = null
        mutableState.update {
            it.copy(askNotificationPermission = false, notice = if (granted) it.notice else NOTIFICATIONS_DENIED_NOTICE)
        }
        if (settings != null && wakeWanted) viewModelScope.launch(Dispatchers.IO) { startWakeWord(settings) }
    }

    private fun notificationsPermitted() =
        Build.VERSION.SDK_INT < NOTIFICATION_PERMISSION_MIN_SDK ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun startWakeWord(settings: WakeWordSettings) {
        if (!wakeWanted) return
        tearDownWakeWord()
        if (!wakeWanted) return
        val controller = WakeWordController(
            runtime.wakeWordDetector(),
            voice,
            micPermitted = ::micPermitted,
            onTranscript = { text -> onHandsFreeTranscript(text) },
            onChange = { w -> mutableState.update { it.copy(wakeWord = w) } },
        )
        wake = controller
        WakeWordHost.hold = controller::hold
        WakeWordHost.release = controller::release
        WakeWordHost.onStopRequested = {
            controller.setEnabled(false)
            voiceStore.save(voiceStore.load().let { it.copy(wakeWord = it.wakeWord.copy(enabled = false)) })
        }
        controller.setEnabled(true)
        if (mutableState.value.wakeWord.enabled) WakeWordService.start(context, settings.listenWhenScreenOff)
    }

    private fun tearDownWakeWord() {
        wake?.setEnabled(false)
        wake = null
        WakeWordHost.hold = null
        WakeWordHost.release = null
        WakeWordHost.onStopRequested = null
        WakeWordService.stop(context)
    }

    override fun onCleared() {
        // A reply still being generated for a screen that is gone would hold the model and the CPU until it ended.
        stopRequested.set(true)
        session.requestStopLocal()
        session.requestStopPcHost()
        importCancelled.set(true)
        wakeWanted = false
        tearDownWakeWord()
        voice.stopListening()
        voice.stopSpeaking()
        tts.shutdown()
    }

    private fun speakModeFromPrefs(): SpeakMode =
        runCatching { SpeakMode.valueOf(prefs.getString(KEY_SPEAK_MODE, null).orEmpty()) }.getOrDefault(SpeakMode.OFF)

    private fun modelKey(provider: CloudProviderSpec) = "model.${provider.id}"

    private fun keyReady(provider: CloudProviderSpec): Boolean =
        vault.inspect(provider.secretId) == SecretState.Present

    private companion object {
        const val KEY_PROVIDER = "provider"
        const val KEY_MEMORY = "memory_enabled"
        const val KEY_AUTO_REMEMBER = "auto_remember_enabled"
        const val AUTO_REMEMBER_TURNS = 6
        const val KEY_WEB_SEARCH = "web_search_enabled"
        const val KEY_SPEAK_MODE = "speak_mode"
        const val KEY_LOCAL_MODEL = "local_model_id"
        const val KEY_CONVERSATION = "conversation_id"
        const val KEY_SKILL = "skill_id"
        const val BYTES_PER_MEGABYTE = 1024L * 1024L
        const val PROGRESS_STEP_BYTES = 64L * 1024L * 1024L
    }
}
