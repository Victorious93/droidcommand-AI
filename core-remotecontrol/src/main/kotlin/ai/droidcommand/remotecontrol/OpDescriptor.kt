package ai.droidcommand.remotecontrol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Per-op descriptor advertised by `capabilities.get`.
 * [kind] wire values: `read` | `action` | `destructive`.
 */
@Serializable
data class OpDescriptor(
    val name: String,
    val kind: OpKind,
    val remoteAllowed: Boolean,
    val requiresConfirm: Boolean,
) {
    init {
        require(name.isNotBlank()) { "op name is required" }
    }
}

@Serializable
enum class OpKind {
    @SerialName("read")
    READ,

    @SerialName("action")
    ACTION,

    @SerialName("destructive")
    DESTRUCTIVE,
    ;

    val wire: String
        get() = when (this) {
            READ -> "read"
            ACTION -> "action"
            DESTRUCTIVE -> "destructive"
        }

    companion object {
        fun fromWire(value: String): OpKind? = when (value) {
            "read" -> READ
            "action" -> ACTION
            "destructive" -> DESTRUCTIVE
            else -> null
        }
    }
}

@Serializable
data class CapabilityAdvertisement(
    val protocolMajor: Int = FleetRemoteEnvelope.PROTOCOL_MAJOR,
    val protocolMinor: Int = FleetRemoteEnvelope.PROTOCOL_MINOR,
    val ops: List<OpDescriptor>,
)

/**
 * Ops the controller itself advertises on its inbound surface.
 * Destructive fleet actions are **not** listed here as auto-runnable;
 * they stay behind mapping → ApprovalPrompt.
 */
object ControllerCapabilityCatalog {
    val OPS: List<OpDescriptor> = listOf(
        OpDescriptor(
            name = FleetRemoteEnvelope.OP_CAPABILITIES_GET,
            kind = OpKind.READ,
            remoteAllowed = true,
            requiresConfirm = false,
        ),
        OpDescriptor(
            name = FleetRemoteEnvelope.OP_REMOTE_COMMAND,
            kind = OpKind.ACTION,
            remoteAllowed = true,
            requiresConfirm = true,
        ),
        OpDescriptor(
            name = FleetRemoteEnvelope.OP_MACRO_STEP,
            kind = OpKind.ACTION,
            remoteAllowed = true,
            requiresConfirm = true,
        ),
        OpDescriptor(
            name = FleetRemoteEnvelope.OP_MACRO_RUN,
            kind = OpKind.ACTION,
            remoteAllowed = true,
            requiresConfirm = true,
        ),
        OpDescriptor(
            name = FleetRemoteEnvelope.OP_NODE_STATUS_GET,
            kind = OpKind.READ,
            remoteAllowed = true,
            requiresConfirm = false,
        ),
        OpDescriptor(
            name = FleetRemoteEnvelope.OP_CONFIG_EXPORT,
            kind = OpKind.ACTION,
            remoteAllowed = false,
            requiresConfirm = false,
        ),
        OpDescriptor(
            name = FleetRemoteEnvelope.OP_CONFIG_IMPORT,
            kind = OpKind.ACTION,
            remoteAllowed = false,
            requiresConfirm = true,
        ),
    )

    fun advertisement(): CapabilityAdvertisement = CapabilityAdvertisement(ops = OPS)

    fun find(name: String): OpDescriptor? = OPS.find { it.name == name }
}
