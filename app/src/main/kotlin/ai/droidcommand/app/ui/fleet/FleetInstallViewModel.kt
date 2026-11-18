package ai.droidcommand.app.ui.fleet

import ai.droidcommand.app.fleet.install.FleetAppInstaller
import ai.droidcommand.app.fleet.install.FleetAutoUpdate
import ai.droidcommand.app.fleet.install.FleetSources
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
import javax.inject.Inject

/** What one fleet card shows about installing it. [actionLabel] null means there is nothing to tap. */
data class FleetInstallUi(
    val actionLabel: String? = null,
    val note: String? = null,
    val busy: Boolean = false,
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

    /** Asks GitHub what is published for every app that has a release source. Network: runs off the main thread. */
    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val next = FleetSources.all.associate { source -> source.fleetId to describe(installer.check(source), installer.isInstalled(source.packageName)) }
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
        setBusy(fleetId, "Looking up the latest version…")
        viewModelScope.launch(Dispatchers.IO) {
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
                setBusy(fleetId, "Downloading ${asset.name}…")
                val apk = installer.download(source, asset)
                setBusy(fleetId, "Handing it to Android's installer…")
                installer.install(source, apk, silentIfAllowed = false)
                if (apk.digestChecked) "Downloaded and checked. Confirm in Android's installer." else "Downloaded. GitHub gave no checksum, so Android's signature check is the only one. Confirm in Android's installer."
            }
            refreshOne(fleetId)
            mutable.update { s ->
                s.copy(message = result.getOrElse { "${source.displayName}: ${it.message ?: "install failed"}" })
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
        finish(fleetId, describe(installer.check(source), installer.isInstalled(source.packageName)), null)
    }

    private fun setBusy(id: String, note: String) =
        mutable.update { it.copy(byId = it.byId + (id to FleetInstallUi(note = note, busy = true))) }

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
