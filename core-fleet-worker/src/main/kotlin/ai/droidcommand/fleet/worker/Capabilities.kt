package ai.droidcommand.fleet.worker

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One operation as the phone sees it RIGHT NOW. `available` means an executor really exists and works (never "a flag is
 * set"); `granted` means the required Android permission/consent is currently held; `ownerInteractionRequired` means the
 * owner must act on the device (e.g. confirm an install) before the job can finish.
 */
data class OperationState(
    val available: Boolean,
    val granted: Boolean = true,
    val ownerInteractionRequired: Boolean = false,
    val reason: String? = null,
)

/** Root is separate from every other capability: grants are per device and may expire. */
data class RootState(val available: Boolean = false, val granted: Boolean = false, val grantExpiresMs: Long? = null)

data class DeviceState(
    val batteryPercent: Int? = null,
    val charging: Boolean? = null,
    val thermal: String = "unknown",
    val freeStorageBytes: Long? = null,
    val foregroundBusy: Boolean = false,
)

data class CapabilityReport(
    val operations: Map<String, OperationState>,
    val root: RootState = RootState(),
    val device: DeviceState = DeviceState(),
    val maxConcurrent: Int = 1,
) {
    fun deviceJson(): JsonObject = buildJsonObject {
        device.batteryPercent?.let { put("battery_percent", it) }
        device.charging?.let { put("charging", it) }
        put("thermal", device.thermal)
        device.freeStorageBytes?.let { put("free_storage_bytes", it) }
        put("foreground_busy", device.foregroundBusy)
    }

    fun toJson(): JsonObject = buildJsonObject {
        put(
            "operations",
            buildJsonObject {
                for ((n, o) in operations.toSortedMap()) put(
                    n,
                    buildJsonObject {
                        put("available", o.available)
                        put("granted", o.granted)
                        if (o.ownerInteractionRequired) put("owner_interaction_required", true)
                        o.reason?.let { put("reason", it) }
                    },
                )
            },
        )
        put(
            "root",
            buildJsonObject {
                put("available", root.available)
                put("granted", root.granted)
                root.grantExpiresMs?.let { put("grant_expires_ms", it) }
            },
        )
        put("device_state", deviceJson())
        put("max_concurrent", maxConcurrent)
    }
}

/**
 * Live capability check. Implemented in :app against real Android APIs (permission state, installed executors, root grant
 * store, BatteryManager, PowerManager thermal status, StatFs). It is called again immediately before every job runs.
 */
fun interface CapabilityProbe {
    fun probe(): CapabilityReport
}
