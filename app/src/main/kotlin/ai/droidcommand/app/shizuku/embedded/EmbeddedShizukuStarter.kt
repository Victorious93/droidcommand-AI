package ai.droidcommand.app.shizuku.embedded

import ai.droidcommand.app.prefs.AdvancedPrefs
import ai.droidcommand.shizuku.EmbeddedStartMethod
import ai.droidcommand.shizuku.EmbeddedStartPolicy
import ai.droidcommand.shizuku.EmbeddedStartSignals
import ai.droidcommand.shizuku.PairingValidation
import ai.droidcommand.shizuku.ShizukuManager
import ai.droidcommand.shizuku.ShizukuState
import ai.droidcommand.shizuku.WirelessPairingInput
import ai.droidcommand.shizuku.WirelessPairingValidator
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import rikka.shizuku.Shizuku
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Starts the embedded Shizuku server. Never requests `API_V23` and never
 * writes [WRITE_SECURE_SETTINGS] unless the user already granted it via adb.
 */
class EmbeddedShizukuStarter(
    private val context: Context,
    private val prefs: AdvancedPrefs,
    private val payload: EmbeddedShizukuPayload,
    private val wireless: WirelessAdbClient,
    private val manager: ShizukuManager,
) {
    fun signals(): EmbeddedStartSignals {
        val binder = binderAlive()
        return EmbeddedStartSignals(
            startOnBoot = prefs.startShizukuOnBoot(),
            rootAvailable = isRootAvailable(),
            sdkInt = Build.VERSION.SDK_INT,
            writeSecureSettingsGranted = hasWriteSecureSettings(),
            wifiConnected = isWifiConnected(),
            wirelessAdbPaired = prefs.shizukuWirelessPaired(),
            externalBinderAlive = binder && externalManagerPresent(),
            embeddedAvailable = payload.isAvailable(),
            embeddedBinderAlive = binder,
        )
    }

    fun startMethod(): EmbeddedStartMethod = EmbeddedStartPolicy.startButtonMethod(signals())

    fun startWireless(input: WirelessPairingInput): String {
        if (EmbeddedStartPolicy.running(signals())) {
            return "Shizuku is already running."
        }
        val parsed = WirelessPairingValidator.validate(input)
        if (parsed is PairingValidation.Error) return parsed.message
        val ok = parsed as PairingValidation.Ok
        val extracted = payload.ensureExtracted()
        val keysDir = keysDir()
        wireless.pair(ok.pairCode, ok.pairPort, keysDir)
        val connectPort = ok.connectPort ?: wireless.discoverConnectPort()
            ?: return "Paired. Type the Wireless debugging port (next to the IP address) and tap Start again."
        wireless.shell(connectPort, extracted.command, keysDir)
        prefs.setShizukuWirelessPaired(true)
        prefs.setShizukuLastConnectPort(connectPort)
        return waitForBinder("Started through wireless debugging. Tap Request Shizuku permission if the status asks.")
    }

    fun startPaired(): String {
        if (EmbeddedStartPolicy.running(signals())) {
            return "Shizuku is already running."
        }
        val extracted = payload.ensureExtracted()
        val port = prefs.shizukuLastConnectPort().takeIf { it in 1..65535 }
            ?: wireless.discoverConnectPort()
            ?: return "Could not find the wireless debugging port. Type it and tap Start."
        wireless.shell(port, extracted.command, keysDir())
        prefs.setShizukuLastConnectPort(port)
        return waitForBinder("Started through the saved wireless pairing. Tap Request Shizuku permission if the status asks.")
    }

    fun startRoot(): String {
        if (EmbeddedStartPolicy.running(signals())) {
            return "Shizuku is already running."
        }
        if (!isRootAvailable()) return "This phone did not accept a root shell."
        val extracted = payload.ensureExtracted()
        val result = runSu(extracted.command)
        if (!result) return "Root start failed. The rest of the app still works."
        return waitForBinder("Started with root. Tap Request Shizuku permission if the status asks.")
    }

    fun enableWirelessDebugging(): Boolean {
        if (!hasWriteSecureSettings()) return false
        val resolver = context.contentResolver
        val wifi = Settings.Global.putInt(resolver, "adb_wifi_enabled", 1)
        Settings.Global.putInt(resolver, Settings.Global.ADB_ENABLED, 1)
        return wifi
    }

    fun isWifiConnected(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    fun isRootAvailable(): Boolean = try {
        val process = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
        val finished = process.waitFor(2, TimeUnit.SECONDS)
        val code = if (finished) process.exitValue() else -1
        process.destroy()
        finished && code == 0
    } catch (_: Throwable) {
        false
    }

    fun hasWriteSecureSettings(): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    private fun runSu(command: String): Boolean = try {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val finished = process.waitFor(15, TimeUnit.SECONDS)
        val code = if (finished) process.exitValue() else -1
        process.destroy()
        finished && code == 0
    } catch (_: Throwable) {
        false
    }

    private fun waitForBinder(success: String): String {
        repeat(20) {
            if (binderAlive()) {
                return success
            }
            Thread.sleep(250)
        }
        return if (binderAlive()) success else "$success The binder is not up yet — wait a moment and refresh."
    }

    private fun binderAlive(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    private fun externalManagerPresent(): Boolean {
        val pm = context.packageManager
        return listOf("moe.shizuku.privileged.api", "rikka.sui").any { pkg ->
            try {
                pm.getPackageInfo(pkg, 0)
                true
            } catch (_: Throwable) {
                false
            }
        }
    }

    private fun keysDir(): File = File(context.filesDir, EmbeddedShizukuPayload.DIR)

    fun granted(): Boolean = manager.state() == ShizukuState.GRANTED
}
