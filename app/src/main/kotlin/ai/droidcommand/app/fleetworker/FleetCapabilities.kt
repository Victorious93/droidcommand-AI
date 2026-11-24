package ai.droidcommand.app.fleetworker

import ai.droidcommand.fleet.worker.CapabilityReport
import ai.droidcommand.fleet.worker.DeviceState
import ai.droidcommand.fleet.worker.OperationState
import ai.droidcommand.fleet.worker.RootState

/**
 * What this phone tells the coordinator it can do, built from plain readings so it is testable without a device.
 * It claims only what is true: the one operation (`hash_files`) is available because it needs nothing but app-private
 * storage, and "granted" is the owner's worker switch, read now. Root is never offered, because no root operation exists.
 */
object FleetCapabilities {
    const val HASH_FILES = "hash_files"

    fun report(workerEnabled: Boolean, batteryPercent: Int?, charging: Boolean?, thermalStatus: Int?, freeBytes: Long?): CapabilityReport =
        CapabilityReport(
            operations = mapOf(HASH_FILES to OperationState(available = true, granted = workerEnabled)),
            root = RootState(),
            device = DeviceState(
                batteryPercent = batteryPercent?.takeIf { it in 0..100 },
                charging = charging,
                thermal = thermalName(thermalStatus),
                freeStorageBytes = freeBytes?.takeIf { it >= 0 },
            ),
            maxConcurrent = 1,
        )

    /** `PowerManager.THERMAL_STATUS_*` (0..6) to the protocol's names; anything else, or no reading, is "unknown". */
    fun thermalName(status: Int?): String = when (status) {
        0 -> "none"
        1 -> "light"
        2 -> "moderate"
        3 -> "severe"
        4 -> "critical"
        5 -> "emergency"
        6 -> "shutdown"
        else -> "unknown"
    }
}
