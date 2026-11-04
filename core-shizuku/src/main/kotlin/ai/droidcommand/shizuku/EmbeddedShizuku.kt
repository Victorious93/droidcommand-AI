package ai.droidcommand.shizuku

/**
 * How built-in Shizuku will be started. The Android host picks one; this
 * module only names the choice so JVM tests can lock the order.
 *
 * Permission to use Shizuku (`API_V23`) is never part of a start method.
 * Start only brings the server up. The user still has to tap Request.
 */
enum class EmbeddedStartMethod {
    /** `su -c` plus the official starter. */
    ROOT,

    /** Android 11+ wireless debugging: pair code + port, then `adb shell` starter. */
    WIRELESS_PAIR,

    /** Already paired. Connect to wireless debugging and run the starter. */
    WIRELESS_PAIRED,

    /**
     * Boot path: `WRITE_SECURE_SETTINGS` was granted once via adb, Wi-Fi is
     * up, turn wireless debugging on, then start through the saved pairing.
     */
    SECURE_SETTINGS_WADB,

    /** Boot fallback: tell the user to tap Start. Nothing is started for them. */
    NOTIFICATION,

    /** Official Shizuku or Sui is already running. Use that binder. */
    EXTERNAL,

    /** This phone cannot start the built-in server (too old, no root, no payload). */
    UNAVAILABLE,
}

/**
 * Facts [EmbeddedStartPolicy] needs. Keep this a plain data class so JVM
 * tests can drive every branch without Android.
 */
data class EmbeddedStartSignals(
    val startOnBoot: Boolean = false,
    val rootAvailable: Boolean = false,
    val sdkInt: Int = 0,
    val writeSecureSettingsGranted: Boolean = false,
    val wifiConnected: Boolean = false,
    val wirelessAdbPaired: Boolean = false,
    val externalBinderAlive: Boolean = false,
    val embeddedAvailable: Boolean = false,
    val embeddedBinderAlive: Boolean = false,
)

/**
 * What a `BOOT_COMPLETED` receiver should do. [DoNothing] is the default
 * because Start on boot is off until the user turns it on.
 */
sealed class EmbeddedBootPlan {
    data object DoNothing : EmbeddedBootPlan()

    data object AlreadyRunning : EmbeddedBootPlan()

    data object StartViaRoot : EmbeddedBootPlan()

    data object EnableWirelessThenStart : EmbeddedBootPlan()

    data object WaitForWifi : EmbeddedBootPlan()

    data object NotifyUser : EmbeddedBootPlan()
}

/**
 * Pairing fields the Advanced screen collects. Validation is here so a typo
 * never reaches a socket.
 */
data class WirelessPairingInput(
    val pairCode: String,
    val pairPort: String,
    val connectPort: String = "",
)

sealed class PairingValidation {
    data class Ok(val pairCode: String, val pairPort: Int, val connectPort: Int?) : PairingValidation()

    data class Error(val message: String) : PairingValidation()
}

object WirelessPairingValidator {
    fun validate(input: WirelessPairingInput): PairingValidation {
        val code = input.pairCode.trim()
        if (!code.matches(Regex("\\d{6}"))) {
            return PairingValidation.Error("Pairing code must be the 6 digits shown on the Wireless debugging screen.")
        }
        val pairPort = input.pairPort.trim().toIntOrNull()
        if (pairPort == null || pairPort !in 1..65535) {
            return PairingValidation.Error("Pairing port must be the number next to the pairing code (1–65535).")
        }
        val connectRaw = input.connectPort.trim()
        val connectPort = if (connectRaw.isEmpty()) {
            null
        } else {
            connectRaw.toIntOrNull()?.takeIf { it in 1..65535 }
                ?: return PairingValidation.Error("Connect port must be the IP-and-port number on the Wireless debugging screen (1–65535).")
        }
        return PairingValidation.Ok(code, pairPort, connectPort)
    }
}

/**
 * Least-surprise start order. External Shizuku/Sui wins when it is already
 * up. Root is the automatic boot path when present. Wireless debugging is
 * Android 11+ only. Start on boot never runs unless the user turned it on.
 */
object EmbeddedStartPolicy {
    const val WIRELESS_MIN_SDK = 30

    fun running(signals: EmbeddedStartSignals): Boolean =
        signals.externalBinderAlive || signals.embeddedBinderAlive

    fun startButtonMethod(signals: EmbeddedStartSignals): EmbeddedStartMethod {
        if (signals.externalBinderAlive) return EmbeddedStartMethod.EXTERNAL
        if (signals.embeddedBinderAlive) return EmbeddedStartMethod.EXTERNAL
        if (!signals.embeddedAvailable) {
            return if (signals.rootAvailable) EmbeddedStartMethod.ROOT else EmbeddedStartMethod.UNAVAILABLE
        }
        if (signals.sdkInt >= WIRELESS_MIN_SDK) {
            return if (signals.wirelessAdbPaired) {
                EmbeddedStartMethod.WIRELESS_PAIRED
            } else {
                EmbeddedStartMethod.WIRELESS_PAIR
            }
        }
        return if (signals.rootAvailable) EmbeddedStartMethod.ROOT else EmbeddedStartMethod.UNAVAILABLE
    }

    fun rootButtonVisible(signals: EmbeddedStartSignals): Boolean =
        signals.rootAvailable && !running(signals)

    fun bootPlan(signals: EmbeddedStartSignals): EmbeddedBootPlan {
        if (!signals.startOnBoot) return EmbeddedBootPlan.DoNothing
        if (running(signals)) return EmbeddedBootPlan.AlreadyRunning
        if (signals.rootAvailable) return EmbeddedBootPlan.StartViaRoot
        if (signals.sdkInt >= WIRELESS_MIN_SDK && signals.writeSecureSettingsGranted && signals.wirelessAdbPaired) {
            return if (signals.wifiConnected) {
                EmbeddedBootPlan.EnableWirelessThenStart
            } else {
                EmbeddedBootPlan.WaitForWifi
            }
        }
        return EmbeddedBootPlan.NotifyUser
    }

    fun bootMethodLabel(signals: EmbeddedStartSignals): EmbeddedStartMethod = when (bootPlan(signals.copy(startOnBoot = true))) {
        EmbeddedBootPlan.AlreadyRunning -> EmbeddedStartMethod.EXTERNAL
        EmbeddedBootPlan.StartViaRoot -> EmbeddedStartMethod.ROOT
        EmbeddedBootPlan.EnableWirelessThenStart, EmbeddedBootPlan.WaitForWifi -> EmbeddedStartMethod.SECURE_SETTINGS_WADB
        EmbeddedBootPlan.NotifyUser, EmbeddedBootPlan.DoNothing -> {
            if (signals.rootAvailable) {
                EmbeddedStartMethod.ROOT
            } else if (signals.sdkInt >= WIRELESS_MIN_SDK && signals.writeSecureSettingsGranted) {
                EmbeddedStartMethod.SECURE_SETTINGS_WADB
            } else {
                EmbeddedStartMethod.NOTIFICATION
            }
        }
    }
}

/**
 * Built-in Shizuku copy. Pairing codes and ADB keys never appear here.
 * [WRITE_SECURE_SETTINGS_COMMAND] is the exact one-time grant Don has to
 * run from a computer if he wants reboot auto-start without root.
 */
object EmbeddedShizukuCopy {
    const val PACKAGE_ID = "ai.droidcommand.app"

    const val SECTION_TITLE = "Built-in Shizuku"

    const val START_BUTTON = "Start"

    const val START_ROOT_BUTTON = "Start with root"

    const val START_ON_BOOT = "Start on boot"

    const val START_ON_BOOT_DEFAULT_OFF =
        "Off until you turn it on. After a reboot Shizuku is not running until something starts it again."

    const val REBOOT_NOTE =
        "Shizuku always stops after a reboot. Turn on Start on boot, or come back here and tap Start."

    const val WRITE_SECURE_SETTINGS_COMMAND =
        "adb shell pm grant ai.droidcommand.app android.permission.WRITE_SECURE_SETTINGS"

    const val WRITE_SECURE_SETTINGS_HELP =
        "One-time, from a computer with USB debugging. This app never grants that permission itself. " +
            "After it is granted, Start on boot can turn wireless debugging back on when Wi-Fi is up."

    const val ATTRIBUTION =
        "Built-in server and starter come from RikkaApps/Shizuku v13.6.0 (Apache-2.0). " +
            "License and NOTICE are in the app assets and third_party/shizuku."

    const val EXTERNAL_FALLBACK =
        "If the official Shizuku app or Sui is already running, this app uses that instead of starting its own copy."

    const val NO_SILENT_GRANT =
        "Start only launches the server. This app does not grant Shizuku permission for you. Each privileged action still asks first."

    val setupSteps: List<String> = listOf(
        "On Android 11 or newer: Settings → Developer options → Wireless debugging. Turn it on.",
        "Tap Pair device with pairing code. Type the 6-digit code and the pairing port here, then tap Start.",
        "Optional: type the Wireless debugging port (the one next to the IP address) so Start can skip discovery.",
        "On a rooted phone you can tap Start with root instead of pairing.",
        "When the status says permission needed, tap Request Shizuku permission. That tap is required; Start does not skip it.",
        "After a reboot, start it again — or turn on Start on boot.",
    )

    fun methodTitle(method: EmbeddedStartMethod): String = when (method) {
        EmbeddedStartMethod.ROOT -> "This phone will start Shizuku with root."
        EmbeddedStartMethod.WIRELESS_PAIR -> "This phone will start Shizuku after you enter the wireless debugging pair code and port."
        EmbeddedStartMethod.WIRELESS_PAIRED -> "This phone will start Shizuku through the wireless debugging pairing you already saved."
        EmbeddedStartMethod.SECURE_SETTINGS_WADB ->
            "After a reboot, this phone will turn wireless debugging on when Wi-Fi is up and start Shizuku through the saved pairing."
        EmbeddedStartMethod.NOTIFICATION ->
            "After a reboot, this app will send a notification. Tap it to start Shizuku. Nothing starts in the background."
        EmbeddedStartMethod.EXTERNAL -> "Official Shizuku or Sui is already running. This app will use that."
        EmbeddedStartMethod.UNAVAILABLE ->
            "This phone cannot start the built-in server. Use Android 11+ wireless debugging, root, or install official Shizuku/Sui."
    }

    fun bootSummary(method: EmbeddedStartMethod): String = "After a reboot: " + when (method) {
        EmbeddedStartMethod.ROOT -> "start with root."
        EmbeddedStartMethod.SECURE_SETTINGS_WADB -> "turn on wireless debugging when Wi-Fi is up, then start through the saved pairing."
        EmbeddedStartMethod.NOTIFICATION -> "show a notification so you can tap Start."
        EmbeddedStartMethod.EXTERNAL -> "do nothing — Shizuku/Sui is already running."
        EmbeddedStartMethod.WIRELESS_PAIR, EmbeddedStartMethod.WIRELESS_PAIRED ->
            "show a notification so you can tap Start (pairing cannot finish itself)."
        EmbeddedStartMethod.UNAVAILABLE -> "show a notification. Built-in start is not available on this phone."
    }

    fun starterCommand(starterPath: String, apkPath: String): String = "$starterPath --apk=$apkPath"
}

/** SharedPreferences keys. Default for Start on boot is false. */
object EmbeddedShizukuPrefs {
    const val KEY_START_ON_BOOT = "shizuku.start_on_boot"
    const val KEY_WADB_PAIRED = "shizuku.wadb.paired"
    const val KEY_WADB_CONNECT_PORT = "shizuku.wadb.connect_port"
    const val DEFAULT_START_ON_BOOT = false
}
