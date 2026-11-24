package ai.droidcommand.app.fleetworker

import ai.droidcommand.fleet.worker.CapabilityProbe
import ai.droidcommand.fleet.worker.CapabilityReport
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs

// Never run on a device. The readings are plain platform reads; everything that decides what they mean is in FleetCapabilities
// (JVM-tested). Called before every job as well as at hello, so a reading is never older than the job it gates.

/** Live battery, thermal and storage readings for the coordinator, plus the owner's worker switch. */
class AndroidCapabilityProbe(
    private val context: Context,
    private val workerEnabled: () -> Boolean,
) : CapabilityProbe {
    override fun probe(): CapabilityReport {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else null
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = if (status < 0) null else status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { (context.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus }.getOrNull()
        } else {
            null
        }
        val free = runCatching { StatFs(context.filesDir.absolutePath).availableBytes }.getOrNull()
        return FleetCapabilities.report(workerEnabled(), percent, charging, thermal, free)
    }
}
