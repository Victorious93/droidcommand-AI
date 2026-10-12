package ai.droidcommand.app.ui.chat

import ai.droidcommand.agent.ConversationStore
import ai.droidcommand.agent.GraphRetriever
import ai.droidcommand.agent.KnowledgeGraph
import ai.droidcommand.agent.Role
import ai.droidcommand.app.documents.DocumentRuntime
import ai.droidcommand.app.documents.DownloadOutcome
import ai.droidcommand.app.documents.EmbedderDownloadSupervisor
import ai.droidcommand.app.localmodels.ImportOutcome
import ai.droidcommand.app.localmodels.ImportedModel
import ai.droidcommand.app.localmodels.LocalModelRuntime
import ai.droidcommand.app.prefs.AdvancedPrefs
import ai.droidcommand.app.voice.VoiceRuntime
import ai.droidcommand.config.EncryptedSecretsVault
import ai.droidcommand.llm.factory.ChatResult
import ai.droidcommand.llm.factory.ChatSession
import ai.droidcommand.llm.factory.CloudProviderCatalog
import ai.droidcommand.llm.factory.CloudProviderSpec
import ai.droidcommand.llm.factory.MemoryResult
import ai.droidcommand.llm.factory.WebSearchCatalog
import ai.droidcommand.llm.local.ModelInstallResult
import ai.droidcommand.rag.AttachResult
import ai.droidcommand.rag.DocumentInfo
import ai.droidcommand.remote.HttpTransport
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
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    val memoryStatus: String? = null,
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
    vault: EncryptedSecretsVault,
    store: ConversationStore,
    transport: HttpTransport,
    graph: KnowledgeGraph,
    private val runtime: VoiceRuntime,
    private val documentRuntime: DocumentRuntime,
    private val advanced: AdvancedPrefs,
    private val onDeviceModels: LocalModelRuntime,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val prefs = context.getSharedPreferences("chat_prefs", Context.MODE_PRIVATE)
    private val memoryEnabled = prefs.getBoolean(KEY_MEMORY, false)
    private val session = ChatSession(
        vault,
        store,
        transport = transport,
        knowledge = if (memoryEnabled) GraphRetriever(graph) else null,
        knowledgeGraph = if (memoryEnabled) graph else null,
        webSearch = WebSearchCatalog.clientFor(vault, transport),
        documents = documentRuntime.retriever,
        local = onDeviceModels.resources,
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

    /** Wake-word settings held while the POST_NOTIFICATIONS prompt is on screen. */
    @Volatile private var awaitingNotificationAnswer: WakeWordSettings? = null

    private val mutableState = MutableStateFlow(initialState())
    val state: StateFlow<ChatUiState> = mutableState.asStateFlow()

    private fun initialState(): ChatUiState {
        val provider = CloudProviderCatalog.byId(prefs.getString(KEY_PROVIDER, null).orEmpty()) ?: CloudProviderCatalog.all.first()
        val model = prefs.getString(modelKey(provider), null) ?: provider.defaultModel
        return ChatUiState(provider = provider, model = model, memoryEnabled = memoryEnabled, webSearchEnabled = prefs.getBoolean(KEY_WEB_SEARCH, false), speakMode = speakModeFromPrefs(), micAvailable = voice.sttAvailable, toolbar = advanced.toolbarToggles(), activeLocalModelId = prefs.getString(KEY_LOCAL_MODEL, null))
    }

    init {
        startWakeWordIfEnabled()
        // Imported models are read from disk: load them off the main thread, and drop a saved choice that no longer exists.
        viewModelScope.launch(Dispatchers.IO) {
            val models = onDeviceModels.list()
            mutableState.update { state ->
                state.copy(localModels = models, activeLocalModelId = state.activeLocalModelId?.takeIf { id -> models.any { it.id == id } })
            }
        }
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
        // History comes from Room, which refuses main-thread access: load it on the IO dispatcher.
        viewModelScope.launch(Dispatchers.IO) {
            val lines = runCatching { session.history() }.getOrDefault(emptyList()).map { ChatLine(it.role == Role.USER, it.content) }
            mutableState.update { if (it.lines.isEmpty()) it.copy(lines = lines) else it }
        }
    }

    fun selectProvider(provider: CloudProviderSpec) {
        prefs.edit {
            putString(KEY_PROVIDER, provider.id)
            remove(KEY_LOCAL_MODEL)
        }
        mutableState.update { it.copy(provider = provider, model = prefs.getString(modelKey(provider), null) ?: provider.defaultModel, activeLocalModelId = null, error = null) }
    }

    /** Chooses an imported on-device model for the following turns. Switching away from another one frees its memory. */
    fun selectLocalModel(modelId: String) {
        val previous = mutableState.value.activeLocalModelId
        prefs.edit { putString(KEY_LOCAL_MODEL, modelId) }
        mutableState.update { it.copy(activeLocalModelId = modelId, error = null) }
        if (previous != null && previous != modelId) viewModelScope.launch(Dispatchers.IO) { session.close() }
    }

    /**
     * Import button result. Copies the picked `.gguf` into app storage, records its SHA-256 and makes it the active
     * on-device model. Runs off the main thread: the copy and the digest read the whole file.
     */
    fun importLocalModel(uri: Uri) {
        if (mutableState.value.localImporting) return
        mutableState.update { it.copy(localImporting = true, localStatus = "Importing…") }
        viewModelScope.launch(Dispatchers.IO) {
            var reportedBytes = 0L
            val outcome = runCatching {
                val name = displayName(uri)
                val size = sizeOf(uri)
                val stream = context.contentResolver.openInputStream(uri) ?: error("The file could not be opened.")
                stream.use {
                    onDeviceModels.importModel(name, it, size) { bytes ->
                        if (bytes - reportedBytes >= PROGRESS_STEP_BYTES) {
                            reportedBytes = bytes
                            mutableState.update { s -> s.copy(localStatus = "Importing… ${bytes / BYTES_PER_MEGABYTE} MB copied") }
                        }
                    }
                }
            }.getOrElse { ImportOutcome.Failed("Import failed: ${it.message ?: it.javaClass.simpleName}.") }
            val models = onDeviceModels.list()
            when (outcome) {
                is ImportOutcome.Imported -> selectImported(outcome.model.id, models, "Imported ${outcome.model.label}. Chat now runs on this device.")
                is ImportOutcome.Duplicate -> selectImported(outcome.id, models, "That file is already imported. Selected it.")
                is ImportOutcome.Rejected -> mutableState.update { it.copy(localImporting = false, localStatus = outcome.reason) }
                is ImportOutcome.Failed -> mutableState.update { it.copy(localImporting = false, localStatus = outcome.reason) }
            }
        }
    }

    private fun selectImported(id: String, models: List<ImportedModel>, status: String) {
        prefs.edit { putString(KEY_LOCAL_MODEL, id) }
        mutableState.update { it.copy(localImporting = false, localModels = models, activeLocalModelId = id, localStatus = status) }
    }

    fun askDeleteLocalModel() = mutableState.update { it.copy(confirmDeleteLocalModel = true) }

    fun dismissDeleteLocalModel() = mutableState.update { it.copy(confirmDeleteLocalModel = false) }

    /** Deletes the active model's file. The model is unloaded first, so nothing keeps the file open. */
    fun confirmDeleteLocalModel() {
        val id = mutableState.value.activeLocalModelId ?: return
        mutableState.update { it.copy(confirmDeleteLocalModel = false, localStatus = "Deleting…") }
        viewModelScope.launch(Dispatchers.IO) {
            session.close()
            onDeviceModels.remove(id)
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

    fun send(text: String) {
        val current = mutableState.value
        if (current.sending || text.isBlank()) return
        mutableState.update { it.copy(sending = true, streaming = "", error = null, notice = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val localId = current.activeLocalModelId
            // Web search sends the message text to a search provider, so it is not used for on-device replies.
            val localNotice = if (localId != null && current.webSearchEnabled) "Web search is not used for on-device replies." else null
            val result = if (localId != null) {
                onDeviceModels.list() // registers the models before the first on-device turn
                runCatching {
                    session.sendLocal(localId, text, useWebSearch = false) { delta ->
                        mutableState.update { it.copy(streaming = it.streaming + delta) }
                    }
                }.getOrElse { ChatResult.Failure(it.message ?: "The on-device model could not run this message.") }
            } else {
                session.send(current.provider, current.model, text, useWebSearch = current.webSearchEnabled) { delta ->
                    mutableState.update { it.copy(streaming = it.streaming + delta) }
                }
            }
            mutableState.update {
                when (result) {
                    is ChatResult.Reply -> it.copy(
                        lines = it.lines + ChatLine(true, text.trim()) + ChatLine(false, result.text),
                        streaming = "",
                        sending = false,
                        notice = result.notice ?: localNotice,
                    ).also { voice.onReply(result.text, it.speakMode) }
                    is ChatResult.Failure -> it.copy(streaming = "", sending = false, error = result.message)
                }
            }
        }
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

    fun newChat() {
        if (mutableState.value.sending) return
        viewModelScope.launch(Dispatchers.IO) {
            session.reset()
            mutableState.update { it.copy(lines = emptyList(), streaming = "", error = null) }
        }
    }

    /** Re-reads the Advanced toolbar choice; called when Chat is shown, since Settings can change it. */
    fun reloadToolbar() {
        mutableState.update { it.copy(toolbar = advanced.toolbarToggles()) }
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

    /**
     * Wake word is opt-in (off by default), needs its model installed and RECORD_AUDIO, and lives only as long as
     * this screen's ViewModel: leaving the chat stops it and its notification. A detection can only start dictation
     * (see [WakeWordController]); it never sends, runs a tool or approves anything.
     */
    private fun startWakeWordIfEnabled() {
        viewModelScope.launch(Dispatchers.IO) {
            val features = runtime.refresh() // hashes the model files: must not run on the main thread
            val settings = voiceStore.load().normalized(features)
            if (!settings.wakeWord.enabled || !features.wakeWord) return@launch
            if (shouldAskNotificationPermission(Build.VERSION.SDK_INT, notificationsPermitted())) {
                // Ask first so the "microphone is on" notification can show; the screen answers via onNotificationPermissionResult.
                awaitingNotificationAnswer = settings.wakeWord
                mutableState.update { it.copy(askNotificationPermission = true) }
            } else {
                startWakeWord(settings.wakeWord)
            }
        }
    }

    /** The screen's answer to the POST_NOTIFICATIONS prompt. Wake word starts either way; a denial is stated, not hidden. */
    fun onNotificationPermissionResult(granted: Boolean) {
        val settings = awaitingNotificationAnswer
        awaitingNotificationAnswer = null
        mutableState.update {
            it.copy(askNotificationPermission = false, notice = if (granted) it.notice else NOTIFICATIONS_DENIED_NOTICE)
        }
        if (settings != null) viewModelScope.launch(Dispatchers.IO) { startWakeWord(settings) }
    }

    private fun notificationsPermitted() =
        Build.VERSION.SDK_INT < NOTIFICATION_PERMISSION_MIN_SDK ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun startWakeWord(settings: WakeWordSettings) {
        val controller = WakeWordController(
            runtime.wakeWordDetector(),
            voice,
            micPermitted = ::micPermitted,
            onTranscript = { text -> mutableState.update { it.copy(pendingInput = text) } },
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

    override fun onCleared() {
        wake?.setEnabled(false)
        wake = null
        WakeWordHost.hold = null
        WakeWordHost.release = null
        WakeWordHost.onStopRequested = null
        WakeWordService.stop(context)
        voice.stopListening()
        voice.stopSpeaking()
        tts.shutdown()
    }

    private fun speakModeFromPrefs(): SpeakMode =
        runCatching { SpeakMode.valueOf(prefs.getString(KEY_SPEAK_MODE, null).orEmpty()) }.getOrDefault(SpeakMode.OFF)

    private fun modelKey(provider: CloudProviderSpec) = "model.${provider.id}"

    private companion object {
        const val KEY_PROVIDER = "provider"
        const val KEY_MEMORY = "memory_enabled"
        const val KEY_WEB_SEARCH = "web_search_enabled"
        const val KEY_SPEAK_MODE = "speak_mode"
        const val KEY_LOCAL_MODEL = "local_model_id"
        const val BYTES_PER_MEGABYTE = 1024L * 1024L
        const val PROGRESS_STEP_BYTES = 64L * 1024L * 1024L
    }
}
