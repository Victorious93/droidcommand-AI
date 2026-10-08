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
import ai.droidcommand.remote.HttpTransport
import android.content.Context
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
    @ApplicationContext context: Context,
) : ViewModel() {
    private val prefs = context.getSharedPreferences("chat_prefs", Context.MODE_PRIVATE)
    private val memoryEnabled = prefs.getBoolean(KEY_MEMORY, false)
    private val session = ChatSession(
        vault,
        store,
        transport = transport,
        knowledge = if (memoryEnabled) GraphRetriever(graph) else null,
        knowledgeGraph = if (memoryEnabled) graph else null,
    )

    private val mutableState = MutableStateFlow(initialState())
    val state: StateFlow<ChatUiState> = mutableState.asStateFlow()

    private fun initialState(): ChatUiState {
        val provider = CloudProviderCatalog.byId(prefs.getString(KEY_PROVIDER, null).orEmpty()) ?: CloudProviderCatalog.all.first()
        val model = prefs.getString(modelKey(provider), null) ?: provider.defaultModel
        return ChatUiState(provider = provider, model = model, memoryEnabled = memoryEnabled)
    }

    init {
        // History comes from Room, which refuses main-thread access: load it on the IO dispatcher.
        viewModelScope.launch(Dispatchers.IO) {
            val lines = runCatching { session.history() }.getOrDefault(emptyList()).map { ChatLine(it.role == Role.USER, it.content) }
            mutableState.update { if (it.lines.isEmpty()) it.copy(lines = lines) else it }
        }
    }

    fun selectProvider(provider: CloudProviderSpec) {
        prefs.edit().putString(KEY_PROVIDER, provider.id).apply()
        mutableState.update { it.copy(provider = provider, model = prefs.getString(modelKey(provider), null) ?: provider.defaultModel, error = null) }
    }

    fun setModel(model: String) {
        prefs.edit().putString(modelKey(mutableState.value.provider), model).apply()
        mutableState.update { it.copy(model = model) }
    }

    fun send(text: String) {
        val current = mutableState.value
        if (current.sending || text.isBlank()) return
        mutableState.update { it.copy(sending = true, streaming = "", error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val result = session.send(current.provider, current.model, text) { delta ->
                mutableState.update { it.copy(streaming = it.streaming + delta) }
            }
            mutableState.update {
                when (result) {
                    is ChatResult.Reply -> it.copy(
                        lines = it.lines + ChatLine(true, text.trim()) + ChatLine(false, result.text),
                        streaming = "",
                        sending = false,
                    )
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

    /** Persists the on/off choice for the next time this screen is opened; does not reconfigure [session] now. */
    fun setMemoryEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MEMORY, enabled).apply()
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

    private fun modelKey(provider: CloudProviderSpec) = "model.${provider.id}"

    private companion object {
        const val KEY_PROVIDER = "provider"
        const val KEY_MEMORY = "memory_enabled"
    }
}
