package ai.droidcommand.app.ui.communications

import ai.droidcommand.communications.ApprovalItem
import ai.droidcommand.communications.CommsAdmin
import ai.droidcommand.communications.CommsBroker
import ai.droidcommand.communications.CommsGrant
import ai.droidcommand.communications.CommsPermission
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import javax.inject.Inject

data class CommunicationsUi(
    val grants: List<CommsGrant> = emptyList(),
    val pending: List<ApprovalItem> = emptyList(),
    val stopped: Boolean = false,
    val available: Int = 0,
    val total: Int = 0,
    val firstReason: String? = null,
    val message: String? = null,
)

/** Victorious Framework: the owner's side of COMPANION_COMMS. Only this screen holds [CommsAdmin]; the transport never does. */
@HiltViewModel
class CommunicationsViewModel @Inject constructor(
    private val admin: CommsAdmin,
    private val broker: CommsBroker,
) : ViewModel() {
    private val mutable = MutableStateFlow(read(null))
    val ui: StateFlow<CommunicationsUi> = mutable.asStateFlow()

    fun refresh() {
        mutable.value = read(null)
    }

    fun approve(id: String) {
        mutable.value = read(if (admin.approve(id)) "Approved. The app must ask again to collect it." else "That request is no longer waiting.")
    }

    fun deny(id: String) {
        mutable.value = read(if (admin.deny(id)) "Denied. Nothing was done." else "That request is no longer waiting.")
    }

    fun revoke(id: String) {
        admin.revoke(id)
        mutable.value = read("Permission removed.")
    }

    fun setStopped(on: Boolean) {
        if (on) admin.engageKill() else admin.releaseKill()
        mutable.value = read(if (on) "Everything automatic is stopped." else "Stop released. Existing permissions apply again.")
    }

    fun allow(app: String, permissions: Set<CommsPermission>, botId: String, ttlHours: Long?, oneTime: Boolean) {
        val msg = runCatching { admin.addGrant(app, permissions, botId.takeIf { it.isNotBlank() }, null, ttlHours?.let { it * 3_600_000L }, oneTime) }
            .fold({ "Allowed." }, { it.message ?: "Could not allow." })
        mutable.value = read(msg)
    }

    private fun read(message: String?): CommunicationsUi {
        val caps = runCatching {
            (Json.parseToJsonElement(broker.discover()) as JsonObject)["capabilities"] as JsonArray
        }.getOrNull().orEmpty().map { it as JsonObject }
        val unavailable = caps.filter { (it["status"] as? JsonPrimitive)?.contentOrNull != "available" }
        return CommunicationsUi(
            grants = admin.grants(),
            pending = admin.pending(),
            stopped = admin.killEngaged,
            available = caps.size - unavailable.size,
            total = caps.size,
            firstReason = unavailable.firstNotNullOfOrNull { (it["reason"] as? JsonPrimitive)?.contentOrNull },
            message = message,
        )
    }
}
