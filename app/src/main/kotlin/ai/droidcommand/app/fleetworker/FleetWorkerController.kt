package ai.droidcommand.app.fleetworker

import ai.droidcommand.activity.ActivityLog
import ai.droidcommand.config.EncryptedSecretsVault
import ai.droidcommand.fleet.worker.HttpsFleetTransport
import ai.droidcommand.fleet.worker.PairingPayload
import ai.droidcommand.security.ApprovalPrompt
import ai.droidcommand.security.JsonFileAuditLog
import ai.droidcommand.security.SecurityPolicy
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Android side of the optional fleet worker: owns the [FleetWorkerEngine], starts and stops the foreground service, and is
 * what the Settings screen and the service talk to. **Nothing starts unless the owner turned the worker on**; the engine itself
 * is only built when something asks for it (the Settings section, or a resume that the owner's own switch allowed).
 *
 * Never run on a device. The wiring compiles and the engine is JVM-tested; the service lifecycle, Keystore identity and live
 * battery/thermal readings are not.
 */
@Singleton
class FleetWorkerController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: FleetWorkerSettings,
    private val appPrompt: ApprovalPrompt,
    private val policy: SecurityPolicy,
    private val auditLog: JsonFileAuditLog,
    private val activityLog: ActivityLog,
    private val vault: EncryptedSecretsVault,
    private val offers: ComposeBatchOfferPrompt,
) {
    val identity: KeystoreWorkerIdentity by lazy { KeystoreWorkerIdentity() }

    private val engine: FleetWorkerEngine by lazy {
        FleetWorkerEngine(
            settings = settings,
            store = VaultWorkerStateStore(vault),
            identity = identity,
            probe = AndroidCapabilityProbe(context) { settings.workerEnabled.value },
            transportFor = { url, spki -> HttpsFleetTransport(url, spki) },
            appPrompt = appPrompt,
            policy = policy,
            auditLog = auditLog,
            activityLog = activityLog,
            offers = offers,
            workRoot = File(context.filesDir, "fleet-worker"),
            deviceName = Build.MODEL?.take(60) ?: "Android phone",
            appVersion = "droidcommand-${versionName()}".take(60),
        )
    }

    val status: StateFlow<FleetWorkerStatus> get() = engine.status

    fun shouldRun(): Boolean = engine.shouldRun()

    /** True when the worker was turned on. It stays off if this phone is not paired (there is nobody to talk to). */
    fun setWorkerEnabled(on: Boolean): Boolean {
        if (on && !engine.status.value.paired) return false
        settings.setWorkerEnabled(on)
        engine.applySettings()
        if (on) startService() else stopService()
        return true
    }

    fun setBatchApprovalEnabled(on: Boolean) {
        settings.setBatchApprovalEnabled(on)
        engine.applySettings()
    }

    /** Blocking (network). The owner has already compared the coordinator's fingerprint on screen. */
    fun pair(payload: PairingPayload): PairOutcome = engine.pair(payload)

    fun unpair() {
        stopService()
        engine.unpair()
    }

    /** Called when the app comes to the foreground: brings the service back only if the owner's switch is on. */
    fun resumeIfEnabled() {
        if (settings.workerEnabled.value && engine.shouldRun()) startService()
    }

    /** The service's loop. Returns when [stop] says so or the owner switches the worker off. */
    fun runLoopForService(stop: () -> Boolean) = engine.runLoop(stop)

    fun refresh() = engine.refresh()

    private fun versionName(): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "unknown"

    private fun startService() {
        try {
            ContextCompat.startForegroundService(context, Intent(context, FleetWorkerService::class.java))
        } catch (e: Exception) {
            // Android refuses a foreground service start from the background; the owner's switch stays on and the next time
            // the app is opened it tries again.
            engine.reportProblem("Could not start the background service: ${e.message?.take(100)}")
        }
    }

    private fun stopService() {
        context.stopService(Intent(context, FleetWorkerService::class.java))
    }
}
