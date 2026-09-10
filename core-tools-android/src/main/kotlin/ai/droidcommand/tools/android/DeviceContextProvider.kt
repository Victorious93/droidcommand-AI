package ai.droidcommand.tools.android

import ai.droidcommand.agent.ContextContribution
import ai.droidcommand.agent.ContextKind
import ai.droidcommand.agent.ContextProvider
import ai.droidcommand.agent.Task

/**
 * Composes [DeviceController.getDeviceInfo]/[DeviceController.getBatteryStatus]/
 * [DeviceController.getNetworkState]/[DeviceController.getStorageInfo] —
 * every real, already-implemented device-state query this module has — into
 * one `core-agent.ContextKind.DEVICE` [ContextContribution] (CAP-001, P0.1).
 *
 * Each of the four is independently `Success`/`Failure`: a `Failure` is
 * rendered as `"<field>: unavailable (<reason>)"` rather than silently
 * omitted or defaulted, matching this module's [NullDeviceController]
 * honesty convention — against a [NullDeviceController], every line reads
 * "unavailable", never a fabricated default.
 */
class DeviceContextProvider(private val controller: DeviceController) : ContextProvider {
    override fun provide(task: Task?): ContextContribution {
        val lines = listOf(
            "device_info" to describeDeviceInfo(controller.getDeviceInfo()),
            "battery" to describeBatteryStatus(controller.getBatteryStatus()),
            "network" to describeNetworkState(controller.getNetworkState()),
            "storage" to describeStorageInfo(controller.getStorageInfo()),
        ).joinToString("\n") { (label, description) -> "$label: $description" }
        return ContextContribution(ContextKind.DEVICE, "device", lines)
    }

    private fun describeDeviceInfo(result: DeviceInfoResult): String = when (result) {
        is DeviceInfoResult.Success -> {
            val info = result.info
            listOfNotNull(
                info.manufacturer,
                info.model,
                info.osVersion?.let { "Android $it" },
                if (info.screenWidthPx != null && info.screenHeightPx != null) {
                    "${info.screenWidthPx}x${info.screenHeightPx}"
                } else {
                    null
                },
            ).joinToString(", ").ifEmpty { "no fields reported" }
        }
        is DeviceInfoResult.Failure -> "unavailable (${result.reason})"
    }

    private fun describeBatteryStatus(result: BatteryStatusResult): String = when (result) {
        is BatteryStatusResult.Success -> {
            val status = result.status
            "${status.levelPercent}%${if (status.isCharging) " (charging)" else ""}"
        }
        is BatteryStatusResult.Failure -> "unavailable (${result.reason})"
    }

    private fun describeNetworkState(result: NetworkStateResult): String = when (result) {
        is NetworkStateResult.Success -> {
            val state = result.state
            "${state.type}${if (state.isConnected) " (connected)" else " (disconnected)"}"
        }
        is NetworkStateResult.Failure -> "unavailable (${result.reason})"
    }

    private fun describeStorageInfo(result: StorageInfoResult): String = when (result) {
        is StorageInfoResult.Success -> "${result.info.freeBytes} free / ${result.info.totalBytes} total bytes"
        is StorageInfoResult.Failure -> "unavailable (${result.reason})"
    }
}
