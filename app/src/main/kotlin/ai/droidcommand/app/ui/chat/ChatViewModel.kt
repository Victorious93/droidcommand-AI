package ai.droidcommand.app.ui.chat

import ai.droidcommand.agent.ConversationStore
import ai.droidcommand.agent.GraphRetriever
import ai.droidcommand.agent.KnowledgeGraph
import ai.droidcommand.agent.Role
import ai.droidcommand.config.EncryptedSecretsVault
import ai.droidcommand.llm.factory.ChatResult
import ai.droidcommand.llm.factory.ChatSession
import ai.droidcommand.llm.factory.CloudProviderCatalog
import ai.droidcommand.llm.factory.CloudProviderSpec
import ai.droidcommand.llm.factory.MemoryResult
import ai.droidcommand.llm.factory.WebSearchCatalog
import ai.droidcommand.app.voice.VoiceRuntime
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
import android.os.Build
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
        return ChatUiState(provider = provider, model = model, memoryEnabled = memoryEnabled, webSearchEnabled = prefs.getBoolean(KEY_WEB_SEARCH, false), speakMode = speakModeFromPrefs(), micAvailable = voice.sttAvailable)
    }

    init {
        startWakeWordIfEnabled()
        // History comes from Room, which refuses main-thread access: load it on the IO dispatcher.
        viewModelScope.launch(Dispatchers.IO) {
            val lines = runCatching { session.history() }.getOrDefault(emptyList()).map { ChatLine(it.role == Role.USER, it.content) }
            mutableState.update { if (it.lines.isEmpty()) it.copy(lines = lines) else it }
        }
    }

    fun selectProvider(provider: CloudProviderSpec) {
        prefs.edit { putString(KEY_PROVIDER, provider.id) }
        mutableState.update { it.copy(provider = provider, model = prefs.getString(modelKey(provider), null) ?: provider.defaultModel, error = null) }
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
            val result = session.send(current.provider, current.model, text, useWebSearch = current.webSearchEnabled) { delta ->
                mutableState.update { it.copy(streaming = it.streaming + delta) }
            }
            mutableState.update {
                when (result) {
                    is ChatResult.Reply -> it.copy(
                        lines = it.lines + ChatLine(true, text.trim()) + ChatLine(false, result.text),
                        streaming = "",
                        sending = false,
                        notice = result.notice,
                    ).also { voice.onReply(result.text, it.speakMode) }
                    is ChatResult.Failure -> it.copy(streaming = "", sending = false, error = result.message)
                }
            }
        }
    }

    fun newChat() {
        if (mutableState.value.sending) return
        viewModelScope.launch(Dispatchers.IO) {
            session.reset()
            mutableState.update { it.copy(lines = emptyList(), streaming = "", error = null) }
        }
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
    }
}
