package ai.droidcommand.app.ui.pchost

import ai.droidcommand.app.ui.settings.KeyStatus
import ai.droidcommand.config.EncryptedSecretsVault
import ai.droidcommand.config.SecretState
import ai.droidcommand.config.SecretStorageException
import ai.droidcommand.llm.pchost.PcHostAddress
import ai.droidcommand.llm.pchost.PcHostAddressParse
import ai.droidcommand.llm.pchost.PcHostClient
import ai.droidcommand.llm.pchost.PcHostCopy
import ai.droidcommand.llm.pchost.PcHostEndpoint
import ai.droidcommand.llm.pchost.PcHostKind
import ai.droidcommand.llm.pchost.PcHostModel
import ai.droidcommand.llm.pchost.PcHostModels
import ai.droidcommand.llm.pchost.PcHostProbe
import ai.droidcommand.llm.pchost.PcHostSettingsStore
import ai.droidcommand.llm.pchost.PcModelLocation
import ai.droidcommand.remote.HttpTransport
import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One server card on the PC-hosted models screen. */
data class PcHostCard(
    val kind: PcHostKind,
    /** The saved, normalised address; null until one is saved. */
    val address: String? = null,
    val addressError: String? = null,
    /** Shown while the saved address uses plain HTTP. */
    val warning: String? = null,
    val keyStatus: KeyStatus = KeyStatus.NOT_SET,
    val testing: Boolean = false,
    /** Outcome of the last test. [testOk] null means not tested since the screen opened. */
    val testOk: Boolean? = null,
    val testMessage: String? = null,
    /** Models the server listed at the last successful test. */
    val models: List<PcHostModel> = emptyList(),
    val model: String = "",
    val modelLocation: String? = null,
    /** True while AI Chat sends its messages to this server. */
    val usedByChat: Boolean = false,
)

/**
 * Compiles; never run on a device. Addresses, the probe, its wording and the store are JVM-tested in
 * `core-llm-factory` (`ai.droidcommand.llm.pchost`); this class only owns screen state and threading.
 * An API key is never read back into the screen, only its status.
 */
@HiltViewModel
class PcHostViewModel @Inject constructor(
    private val store: PcHostSettingsStore,
    private val vault: EncryptedSecretsVault,
    transport: HttpTransport,
    @ApplicationContext context: Context,
) : ViewModel() {
    private val prefs = context.getSharedPreferences(PcHostChoice.CHAT_PREFS, Context.MODE_PRIVATE)
    private val client = PcHostClient(transport)
    private val tests = HashMap<PcHostKind, Job>()

    private val mutableCards = MutableStateFlow(PcHostKind.entries.map { PcHostCard(it, keyStatus = keyStatus(it)) })
    val cards: StateFlow<List<PcHostCard>> = mutableCards.asStateFlow()

    init {
        // The store is a file: read it off the main thread.
        viewModelScope.launch(Dispatchers.IO) { PcHostKind.entries.forEach(::reload) }
    }

    /** Saves [input] as this kind's address and asks the server for its models. A rejected address changes nothing. */
    fun saveAndTest(kind: PcHostKind, input: String) {
        val address = when (val parsed = PcHostAddress.parse(kind, input)) {
            is PcHostAddressParse.Rejected -> return update(kind) { it.copy(addressError = parsed.reason) }
            is PcHostAddressParse.Ok -> parsed.address
        }
        tests[kind]?.cancel()
        update(kind) { it.copy(addressError = null, testing = true, testOk = null, testMessage = "Asking ${address.base}…") }
        tests[kind] = viewModelScope.launch(Dispatchers.IO) {
            val before = store.load(kind)
            // A different server does not keep the model picked on the old one.
            val saved = if (before?.address == address.base) before else PcHostEndpoint(address.base)
            store.save(kind, saved)
            reload(kind)
            val key = runCatching { vault.getSecret(kind.secretId) }.getOrNull()
            val probe = runCatching { client.probe(kind, address, key) }
                .getOrElse { PcHostProbe.Failed(ai.droidcommand.llm.pchost.PcHostProblem.SERVER_ERROR, "The test could not run: ${it.message ?: it.javaClass.simpleName}") }
            update(kind) { card ->
                when (probe) {
                    is PcHostProbe.Reachable -> card.copy(
                        testing = false,
                        testOk = true,
                        testMessage = PcHostCopy.reachable(kind, probe.serverVersion, probe.models, saved.model),
                        models = probe.models,
                    )
                    is PcHostProbe.Failed -> card.copy(testing = false, testOk = false, testMessage = probe.message)
                }
            }
        }
    }

    fun cancelTest(kind: PcHostKind) {
        tests.remove(kind)?.cancel()
        update(kind) { it.copy(testing = false, testMessage = "Test cancelled.") }
    }

    /** Picks a model the server listed. Its location (on the PC or cloud-backed) is saved with it. */
    fun pickModel(kind: PcHostKind, model: PcHostModel) = saveModel(kind, model.id, model.location, model.upstream)

    /** For a server that lists no models: the name is used as typed, and where it runs is unknown. */
    fun typeModel(kind: PcHostKind, name: String) {
        val trimmed = name.trim()
        if (trimmed.isNotEmpty()) saveModel(kind, trimmed, PcModelLocation.UNKNOWN, null)
    }

    private fun saveModel(kind: PcHostKind, model: String, location: PcModelLocation, upstream: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            val saved = store.load(kind) ?: return@launch
            store.save(kind, saved.copy(model = model, location = location, upstream = upstream))
            reload(kind)
        }
    }

    /** Makes AI Chat send its messages to this server. The on-device choice is cleared; the cloud provider is kept for later. */
    fun useInChat(kind: PcHostKind) {
        prefs.edit {
            putString(PcHostChoice.KEY_PC_HOST, kind.id)
            remove(PcHostChoice.KEY_LOCAL_MODEL)
        }
        PcHostKind.entries.forEach { k -> update(k) { it.copy(usedByChat = k == kind) } }
    }

    fun stopUsingInChat(kind: PcHostKind) {
        if (prefs.getString(PcHostChoice.KEY_PC_HOST, null) == kind.id) prefs.edit { remove(PcHostChoice.KEY_PC_HOST) }
        update(kind) { it.copy(usedByChat = false) }
    }

    /** Removes the saved address and model. The key, if any, is removed separately. */
    fun forget(kind: PcHostKind) {
        tests.remove(kind)?.cancel()
        stopUsingInChat(kind)
        viewModelScope.launch(Dispatchers.IO) {
            store.save(kind, null)
            update(kind) { PcHostCard(kind, keyStatus = keyStatus(kind)) }
        }
    }

    fun saveKey(kind: PcHostKind, key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return
        try {
            vault.putSecret(kind.secretId, trimmed)
        } catch (_: SecretStorageException) {
            return update(kind) { it.copy(keyStatus = KeyStatus.STORAGE_ERROR) }
        }
        update(kind) { it.copy(keyStatus = keyStatus(kind)) }
    }

    fun clearKey(kind: PcHostKind) {
        try {
            vault.revokeSecret(kind.secretId)
        } catch (_: SecretStorageException) {
            return update(kind) { it.copy(keyStatus = KeyStatus.STORAGE_ERROR) }
        }
        update(kind) { it.copy(keyStatus = keyStatus(kind)) }
    }

    private fun reload(kind: PcHostKind) {
        val saved = runCatching { store.load(kind) }.getOrNull()
        val parsed = saved?.let { (PcHostAddress.parse(kind, it.address) as? PcHostAddressParse.Ok)?.address }
        val active = prefs.getString(PcHostChoice.KEY_PC_HOST, null) == kind.id
        update(kind) {
            it.copy(
                address = saved?.address,
                warning = parsed?.warning,
                model = saved?.model.orEmpty(),
                modelLocation = saved?.takeIf { s -> s.model.isNotBlank() }?.let { s -> PcHostModels.locationLabel(s.location, s.upstream) },
                usedByChat = active,
            )
        }
    }

    private fun update(kind: PcHostKind, change: (PcHostCard) -> PcHostCard) =
        mutableCards.update { cards -> cards.map { if (it.kind == kind) change(it) else it } }

    private fun keyStatus(kind: PcHostKind): KeyStatus = when (vault.inspect(kind.secretId)) {
        SecretState.Present -> KeyStatus.SAVED
        SecretState.Absent -> KeyStatus.NOT_SET
        SecretState.Unrecoverable -> KeyStatus.NEEDS_REENTRY
        SecretState.StorageUnavailable -> KeyStatus.STORAGE_ERROR
    }
}

/** The `chat_prefs` entries that say where AI Chat sends a message. Shared so the screens that write them cannot drift. */
object PcHostChoice {
    const val CHAT_PREFS = "chat_prefs"

    /** A [PcHostKind.id] while Chat uses a PC server; absent otherwise. Wins over [KEY_LOCAL_MODEL]; both are never set together. */
    const val KEY_PC_HOST = "pc_host_kind"
    const val KEY_LOCAL_MODEL = "local_model_id"
}
