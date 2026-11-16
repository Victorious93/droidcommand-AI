package ai.droidcommand.app.systemsettings

import ai.droidcommand.systemsettings.DeviceContext
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Process
import android.view.Display

/** Reads what the catalog needs to know about this phone: Android version, user, and the refresh rates the display really supports. */
object DeviceFacts {
    fun context(appContext: Context): DeviceContext = DeviceContext(
        sdkInt = Build.VERSION.SDK_INT,
        release = Build.VERSION.RELEASE,
        manufacturer = Build.MANUFACTURER.orEmpty(),
        model = Build.MODEL.orEmpty(),
        fingerprint = Build.FINGERPRINT.orEmpty(),
        userId = Process.myUid() / 100_000,
        supportedRefreshRatesHz = supportedRates(appContext),
    )

    /** Rates of the modes that have the display's current resolution, so a rate that needs a different resolution is not offered. */
    fun supportedRates(context: Context): List<Float> = runCatching {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return emptyList()
        val current = display.mode
        display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .map { Math.round(it.refreshRate * 10f) / 10f }
            .distinct()
            .sorted()
    }.getOrDefault(emptyList())

    /** The rate the display is running at this moment. It is an observation, not the stored preference. */
    fun observedRate(context: Context): Float? = runCatching {
        (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)?.refreshRate
    }.getOrNull()
}
