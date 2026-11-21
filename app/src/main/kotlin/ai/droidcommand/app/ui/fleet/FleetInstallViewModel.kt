package ai.droidcommand.app.ui.fleet

import ai.droidcommand.app.fleet.FleetCatalog
import ai.droidcommand.app.fleet.FleetExtraApps
import ai.droidcommand.app.fleet.install.FleetAppInstaller
import ai.droidcommand.app.fleet.install.FleetAutoUpdate
import ai.droidcommand.app.fleet.install.FleetPrereleasePref
import ai.droidcommand.app.fleet.install.FleetSources
import ai.droidcommand.fleet.update.FleetDownloadQueue
import ai.droidcommand.fleet.update.FleetDownloadRow
import ai.droidcommand.fleet.update.ReleaseAsset
import ai.droidcommand.fleet.update.UpdateDecision
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
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
    /** One row per app in the "Download your apps" list, in display order. Empty until the first look-up finishes. */
    val rows: List<FleetDownloadRow> = emptyList(),
    /** Fleet ids (of apps that have a release source) whose package is on this phone. */
    val installedIds: Set<String> = emptySet(),
    /** True while "Download all" is working through its queue. */
    val bulkBusy: Boolean = false,
    /** Owner's opt-in: offer pre-releases in this list. Off by default. Auto-update never uses it. */
    val includePrereleases: Boolean = false,
)

@HiltViewModel
class FleetInstallViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val installer = FleetAppInstaller(context)
    private val mutable = MutableStateFlow(
        FleetInstallState(
            autoUpdate = FleetAutoUpdate.isEnabled(context),
            includePrereleases = FleetPrereleasePref.isEnabled(context),
        ),
    )
    private val decisions = ConcurrentHashMap<String, UpdateDecision>()

    @Volatile
    private var lastLookupMs = 0L
    val state: StateFlow<FleetInstallState> = mutable.asStateFlow()

    /**
     * Asks GitHub what is published for every app that has a release source. Network: runs off the main thread.
     * GitHub allows few unauthenticated calls per hour, so a non-[force] refresh is skipped when the last look-up is
     * recent; an install finishing or the owner pulling to refresh passes force = true.
     */
    fun refresh(force: Boolean = true) {
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            if (!force && decisions.isNotEmpty() && now - lastLookupMs < MIN_LOOKUP_GAP_MS) return@launch
            lastLookupMs = now
            val looked = coroutineScope {
                val pre = mutable.value.includePrereleases
                FleetSources.all.map { source -> async { source.fleetId to installer.check(source, pre) } }.awaitAll()
            }
            looked.forEach { (id, decision) -> decisions[id] = decision }
            publishDecisions()
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
            val decision = installer.check(source, mutable.value.includePrereleases)
            decisions[fleetId] = decision
            val asset = assetOf(decision)
            if (asset == null) {
                restore(fleetId)
                mutable.update { it.copy(message = "Nothing to install right now.") }
                return@launch
            }
            val result = runCatching { downloadAndHandOff(fleetId, source.displayName, asset) }
            restore(fleetId)
            mutable.update { s ->
                s.copy(message = result.getOrElse { "${source.displayName}: ${it.message ?: "install failed"}" })
            }
        }
    }

    /**
     * "Download all": walks the apps that really have a release APK (see [FleetDownloadQueue]) one after another, using the
     * look-up already on screen so it does not spend more GitHub calls. Each app is downloaded, checked and handed to
     * Android's installer, which asks the owner to confirm every install itself; nothing here installs silently and nothing
     * is paired. One failure does not stop the rest.
     */
    fun installAll() {
        if (mutable.value.bulkBusy) return
        val queue = FleetDownloadQueue.queue(mutable.value.rows)
        if (queue.isEmpty()) {
            mutable.update { it.copy(message = "Nothing is published to download right now.") }
            return
        }
        if (!installer.canInstall()) {
            runCatching { context.startActivity(installer.unknownSourcesIntent()) }
            mutable.update { it.copy(message = "Allow DroidCommand to install apps, then tap Download all again.") }
            return
        }
        mutable.update { it.copy(bulkBusy = true, message = "Downloading ${queue.size} app${if (queue.size == 1) "" else "s"}…") }
        viewModelScope.launch(Dispatchers.IO) {
            var handedOver = 0
            val failed = mutableListOf<String>()
            for (source in queue) {
                val asset = decisions[source.fleetId]?.let(::assetOf)
                if (asset == null) {
                    failed += "${source.displayName} (nothing to download)"
                    continue
                }
                setBusy(source.fleetId, "Downloading ${asset.name}…")
                runCatching { downloadAndHandOff(source.fleetId, source.displayName, asset) }
                    .onSuccess {
                        handedOver++
                        mutable.update { s ->
                            s.copy(byId = s.byId + (source.fleetId to FleetInstallUi(note = "Handed to Android. Confirm the install when it asks.")))
                        }
                    }
                    .onFailure {
                        failed += "${source.displayName} (${it.message ?: "failed"})"
                        restore(source.fleetId)
                    }
            }
            val summary = buildString {
                append("Handed $handedOver app${if (handedOver == 1) "" else "s"} to Android's installer. Confirm each one when it asks.")
                if (failed.isNotEmpty()) append(" Not done: ${failed.joinToString("; ")}.")
            }
            mutable.update { it.copy(bulkBusy = false, message = summary) }
        }
    }

    /** Turns pre-release downloads on or off for this list and looks everything up again under the new rule. */
    fun setIncludePrereleases(on: Boolean) {
        FleetPrereleasePref.setEnabled(context, on)
        mutable.update { it.copy(includePrereleases = on) }
        refresh(force = true)
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

    /** Back to what the stored look-up says, without another network call. */
    private fun restore(fleetId: String) {
        val source = FleetSources.forId(fleetId) ?: return
        val decision = decisions[fleetId] ?: return
        finish(fleetId, describe(decision, installer.isInstalled(source.packageName)), null)
    }

    private fun assetOf(decision: UpdateDecision): ReleaseAsset? = when (decision) {
        is UpdateDecision.Install -> decision.asset
        is UpdateDecision.Update -> decision.asset
        else -> null
    }

    /** Downloads [asset], checks it and gives it to Android's installer. Returns the owner-facing result line. */
    private fun downloadAndHandOff(fleetId: String, displayName: String, asset: ReleaseAsset): String {
        val source = checkNotNull(FleetSources.forId(fleetId)) { "$displayName has no download source." }
        setBusy(fleetId, "Downloading ${asset.name}…")
        val apk = installer.download(source, asset)
        setBusy(fleetId, "Handing it to Android's installer…")
        installer.install(source, apk, silentIfAllowed = false)
        return if (apk.digestChecked) {
            "Downloaded and checked. Confirm in Android's installer."
        } else {
            "Downloaded. GitHub gave no checksum, so Android's signature check is the only one. Confirm in Android's installer."
        }
    }

    /** Rebuilds the per-card notes and the download list from the stored look-ups. Busy cards keep their progress note. */
    private fun publishDecisions() {
        val installed = FleetSources.all.filter { installer.isInstalled(it.packageName) }.map { it.fleetId }.toSet()
        val next = FleetSources.all.mapNotNull { source ->
            decisions[source.fleetId]?.let { source.fleetId to describe(it, source.fleetId in installed) }
        }.toMap()
        val rows = downloadDirectory().map { (id, name) ->
            val source = FleetSources.forId(id)
            FleetDownloadRow(id, name, source, source?.let { decisions[id] })
        }
        mutable.update { state ->
            val busy = state.byId.filterValues { ui -> ui.busy }
            state.copy(
                byId = next.filterKeys { id -> id !in busy } + busy,
                rows = rows,
                installedIds = installed,
            )
        }
    }

    /** Every app the list shows, in order: the Fleet roster, then the display-only extras, then any source left over. */
    private fun downloadDirectory(): List<Pair<String, String>> {
        val ordered = FleetCatalog.all.map { it.id to it.name } + FleetExtraApps.all.map { it.id to it.name }
        val known = ordered.map { it.first }.toSet()
        return ordered + FleetSources.all.filter { it.fleetId !in known }.map { it.fleetId to it.displayName }
    }

    private fun setBusy(id: String, note: String) =
        mutable.update { it.copy(byId = it.byId + (id to FleetInstallUi(note = note, busy = true))) }

    private fun finish(id: String, ui: FleetInstallUi, message: String?) =
        mutable.update { it.copy(byId = it.byId + (id to ui), message = message ?: it.message) }

    private fun describe(decision: UpdateDecision, installed: Boolean): FleetInstallUi = when (decision) {
        is UpdateDecision.Install -> FleetInstallUi(
            actionLabel = "Download & install",
            note = "Latest: ${decision.release.tag}" + buildLabel(decision.release.prerelease, decision.isDebugBuild),
        )
        is UpdateDecision.Update -> FleetInstallUi(
            actionLabel = "Update to ${decision.release.tag}",
            note = "Installed ${decision.from}, newer ${decision.release.tag} is published." + buildLabel(decision.release.prerelease, decision.isDebugBuild),
        )
        is UpdateDecision.UpToDate -> FleetInstallUi(note = "Up to date (${decision.version}).")
        is UpdateDecision.NothingPublished -> FleetInstallUi(
            note = if (installed) null else "No downloadable APK is published yet. Get it from its official source.",
        )
        is UpdateDecision.Unknown -> FleetInstallUi(note = null)
    }

    private fun buildLabel(prerelease: Boolean, debug: Boolean): String = when {
        prerelease && debug -> " (pre-release, debug build)"
        prerelease -> " (pre-release)"
        debug -> " (debug build)"
        else -> ""
    }

    private companion object {
        /** Do not look GitHub up again within this long unless forced. about 20 unauthenticated calls per look-up add up fast. */
        const val MIN_LOOKUP_GAP_MS = 10 * 60 * 1000L
    }
}
