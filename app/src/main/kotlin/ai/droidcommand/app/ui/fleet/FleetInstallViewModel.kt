package ai.droidcommand.app.ui.fleet

import ai.droidcommand.app.fleet.install.FleetAppInstaller
import ai.droidcommand.app.fleet.install.FleetAutoUpdate
import ai.droidcommand.app.fleet.install.FleetSources
import ai.droidcommand.fleet.update.ApkDownloadStopped
import ai.droidcommand.fleet.update.UpdateDecision
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/** What one fleet card shows about installing it. [actionLabel] null means there is nothing to tap. */
data class FleetInstallUi(
    val actionLabel: String? = null,
    val note: String? = null,
    val busy: Boolean = false,
    /** True only while the APK is downloading: Stop and Cancel are offered then (not while it is handed to Android). */
    val canStop: Boolean = false,
    /** Bytes a stopped or interrupted download kept on disk. More than 0 means Resume instead of the action label. */
    val partialBytes: Long = 0L,
)

data class FleetInstallState(
    val byId: Map<String, FleetInstallUi> = emptyMap(),
    val autoUpdate: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class FleetInstallViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val installer = FleetAppInstaller(context)
    private val mutable = MutableStateFlow(FleetInstallState(autoUpdate = FleetAutoUpdate.isEnabled(context)))
    val state: StateFlow<FleetInstallState> = mutable.asStateFlow()

    /** Per running download: set to true to stop it. Stop keeps what is downloaded; Cancel also deletes it. */
    private val stopFlags = ConcurrentHashMap<String, AtomicBoolean>()
    private val discardRequests = ConcurrentHashMap.newKeySet<String>()

    /** Asks GitHub what is published for every app that has a release source. Network: runs off the main thread. */
    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val next = FleetSources.all.associate { source -> source.fleetId to describe(installer.check(source), installer.isInstalled(source.packageName)).withPartial(installer.partialBytes(source)) }
            mutable.update { it.copy(byId = it.byId.filterValues { ui -> ui.busy } + next.filterKeys { id -> it.byId[id]?.busy != true }) }
        }
    }

    /** Download, verify, then hand to Android's installer. The owner confirms in Android's own screen. */
    fun install(fleetId: String) {
        val source = FleetSources.forId(fleetId) ?: return
        if (!installer.canInstall()) {
            runCatching { context.startActivity(installer.unknownSourcesIntent()) }
            mutable.update { it.copy(message = "Allow DroidCommand to install apps, then tap the button again.") }
            return
        }
        val stop = AtomicBoolean(false)
        if (stopFlags.putIfAbsent(fleetId, stop) != null) return
        setBusy(fleetId, "Looking up the latest version…")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val decision = installer.check(source)
                val asset = when (decision) {
                    is UpdateDecision.Install -> decision.asset
                    is UpdateDecision.Update -> decision.asset
                    else -> {
                        finish(fleetId, describe(decision, installer.isInstalled(source.packageName)), "Nothing to install right now.")
                        return@launch
                    }
                }
                val result = runCatching {
                    setBusy(fleetId, "Downloading ${asset.name}…", canStop = true)
                    var lastUpdateNanos = 0L
                    val apk = installer.download(
                        source,
                        asset,
                        // Progress arrives on every read; the card only needs it a few times a second.
                        onProgress = { received, total ->
                            val now = System.nanoTime()
                            if (now - lastUpdateNanos >= PROGRESS_INTERVAL_NANOS) {
                                lastUpdateNanos = now
                                setBusy(fleetId, "Downloading ${asset.name}… ${megabytes(received)}" + if (total > 0) " of ${megabytes(total)}" else "", canStop = true)
                            }
                        },
                        cancelled = stop::get,
                    )
                    setBusy(fleetId, "Handing it to Android's installer…")
                    installer.install(source, apk, silentIfAllowed = false)
                    if (apk.digestChecked) "Downloaded and checked. Confirm in Android's installer." else "Downloaded. GitHub gave no checksum, so Android's signature check is the only one. Confirm in Android's installer."
                }
                // The transfer has returned, so nothing is writing the partial file: a Cancel can delete it now.
                val discard = discardRequests.remove(fleetId)
                if (discard) installer.discardPartial(source)
                refreshOne(fleetId)
                mutable.update { s ->
                    s.copy(
                        message = when {
                            discard -> "${source.displayName}: cancelled. What was downloaded was deleted."
                            result.exceptionOrNull() is ApkDownloadStopped -> "${source.displayName}: stopped. Resume continues from where it stopped."
                            else -> result.getOrElse { "${source.displayName}: ${it.message ?: "install failed"}" }
                        },
                    )
                }
            } finally {
                stopFlags.remove(fleetId)
            }
        }
    }

    /** Stop: ends the download and keeps what is downloaded, so Resume continues from it. Takes effect within a moment. */
    fun stop(fleetId: String) {
        stopFlags[fleetId]?.set(true)
    }

    /** Cancel: ends the download and deletes what was downloaded. */
    fun cancel(fleetId: String) {
        val source = FleetSources.forId(fleetId) ?: return
        val running = stopFlags[fleetId]
        if (running != null) {
            // The download deletes its own partial file once it has stopped, so nothing is deleted under a writer.
            discardRequests += fleetId
            running.set(true)
        } else {
            viewModelScope.launch(Dispatchers.IO) {
                installer.discardPartial(source)
                refreshOne(fleetId)
                mutable.update { it.copy(message = "${source.displayName}: cancelled. What was downloaded was deleted.") }
            }
        }
    }

    fun setAutoUpdate(on: Boolean) {
        FleetAutoUpdate.setEnabled(context, on)
        mutable.update {
            it.copy(
                autoUpdate = on,
                message = if (on && !installer.canInstall()) "Also allow DroidCommand to install apps in Android settings, or updates will wait." else null,
            )
        }
        if (on && !installer.canInstall()) runCatching { context.startActivity(installer.unknownSourcesIntent()) }
    }

    private fun refreshOne(fleetId: String) {
        val source = FleetSources.forId(fleetId) ?: return
        finish(fleetId, describe(installer.check(source), installer.isInstalled(source.packageName)).withPartial(installer.partialBytes(source)), null)
    }

    private fun setBusy(id: String, note: String, canStop: Boolean = false) =
        mutable.update { it.copy(byId = it.byId + (id to FleetInstallUi(note = note, busy = true, canStop = canStop))) }

    /** A kept partial file turns the action into Resume, with the amount shown. */
    private fun FleetInstallUi.withPartial(bytes: Long): FleetInstallUi =
        if (bytes > 0L && actionLabel != null) copy(partialBytes = bytes, note = "Stopped at ${megabytes(bytes)}. " + note.orEmpty()) else this

    private fun megabytes(bytes: Long): String = "%.1f MB".format(bytes / (1024.0 * 1024.0))

    private companion object {
        const val PROGRESS_INTERVAL_NANOS = 250_000_000L
    }

    private fun finish(id: String, ui: FleetInstallUi, message: String?) =
        mutable.update { it.copy(byId = it.byId + (id to ui), message = message ?: it.message) }

    private fun describe(decision: UpdateDecision, installed: Boolean): FleetInstallUi = when (decision) {
        is UpdateDecision.Install -> FleetInstallUi(
            actionLabel = "Download & install",
            note = "Latest: ${decision.release.tag}" + if (decision.isDebugBuild) " (debug build)" else "",
        )
        is UpdateDecision.Update -> FleetInstallUi(
            actionLabel = "Update to ${decision.release.tag}",
            note = "Installed ${decision.from}, newer ${decision.release.tag} is published." + if (decision.isDebugBuild) " (debug build)" else "",
        )
        is UpdateDecision.UpToDate -> FleetInstallUi(note = "Up to date (${decision.version}).")
        is UpdateDecision.NothingPublished -> FleetInstallUi(
            note = if (installed) null else "No downloadable APK is published yet. Get it from its official source.",
        )
        is UpdateDecision.Unknown -> FleetInstallUi(note = null)
    }
}
