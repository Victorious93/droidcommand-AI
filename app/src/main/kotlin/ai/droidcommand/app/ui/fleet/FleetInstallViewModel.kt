package ai.droidcommand.app.ui.fleet

import ai.droidcommand.app.fleet.FleetCatalog
import ai.droidcommand.app.fleet.FleetExtraApps
import ai.droidcommand.app.fleet.install.FleetAppInstaller
import ai.droidcommand.app.fleet.install.FleetAutoUpdate
import ai.droidcommand.app.fleet.install.FleetPrereleasePref
import ai.droidcommand.app.fleet.install.FleetSources
import ai.droidcommand.fleet.update.ApkDownloadStopped
import ai.droidcommand.fleet.update.FleetAppSource
import ai.droidcommand.fleet.update.FleetAutoUpdateMode
import ai.droidcommand.fleet.update.FleetDownloadQueue
import ai.droidcommand.fleet.update.FleetDownloadRow
import ai.droidcommand.fleet.update.FleetRelease
import ai.droidcommand.fleet.update.FleetReleaseOption
import ai.droidcommand.fleet.update.FleetReleaseOptions
import ai.droidcommand.fleet.update.ReleaseAsset
import ai.droidcommand.fleet.update.UpdateDecision
import android.content.Context
import android.os.Build
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
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/** What one fleet card shows about installing it. [actionLabel] null means there is nothing to tap. */
data class FleetInstallUi(
    val actionLabel: String? = null,
    val note: String? = null,
    val busy: Boolean = false,
    /** True only while the APK is downloading: Stop and Cancel are offered then (not while it is handed to Android). */
    val canStop: Boolean = false,
    /** Bytes a stopped or interrupted download kept on disk **for the default action's asset**. More than 0 means Resume. */
    val partialBytes: Long = 0L,
    /** Any kept partial of this app, whichever release it belongs to. A picked version resumes only when its asset matches. */
    val kept: KeptDownload? = null,
)

/** A stopped or interrupted download left on disk: its size, and the asset address it came from (null when unrecorded). */
data class KeptDownload(val bytes: Long, val assetUrl: String?)

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
    /** Every non-draft release of each app, for its version menu. An app missing here, or mapped to an empty list, has none. */
    val optionsById: Map<String, List<FleetReleaseOption>> = emptyMap(),
    /** Apps whose release list could not be fetched, so "none" is not claimed for them. */
    val lookupFailedIds: Set<String> = emptySet(),
    /** The version tag the owner picked from an app's menu. Absent means "the latest by the default rule". */
    val selectedTagById: Map<String, String> = emptyMap(),
    /** The auto-update mode in force for each app that has a release source. */
    val autoModeById: Map<String, FleetAutoUpdateMode> = emptyMap(),
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
    private val releases = ConcurrentHashMap<String, List<FleetRelease>>()
    private val failedLookups = ConcurrentHashMap.newKeySet<String>()

    /** Per running download: set to true to stop it. Stop keeps what is downloaded; Cancel also deletes it. */
    private val stopFlags = ConcurrentHashMap<String, AtomicBoolean>()
    private val discardRequests = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var lastLookupMs = 0L
    val state: StateFlow<FleetInstallState> = mutable.asStateFlow()

    init {
        publishAutoModes()
    }

    /**
     * Asks GitHub what is published for every app that has a release source. Network: runs off the main thread.
     * GitHub allows few unauthenticated calls per hour, so a non-[force] refresh is skipped when the last look-up is
     * recent; an install finishing or the owner pulling to refresh passes force = true.
     */
    fun refresh(force: Boolean = true) {
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            if (!force && decisions.isNotEmpty() && failedLookups.isEmpty() && now - lastLookupMs < MIN_LOOKUP_GAP_MS) return@launch
            lastLookupMs = now
            val looked = coroutineScope {
                FleetSources.all.map { source -> async { source.fleetId to installer.fetchReleases(source) } }.awaitAll()
            }
            looked.forEach { (id, result) ->
                result
                    .onSuccess { list ->
                        releases[id] = list
                        failedLookups.remove(id)
                    }
                    .onFailure {
                        releases.remove(id)
                        failedLookups.add(id)
                    }
            }
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
        if (stopFlags.containsKey(fleetId)) return
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
            val outcome = downloadWithControls(source, asset)
            restore(fleetId)
            mutable.update { it.copy(message = if (outcome.handedOver) outcome.message else "${source.displayName}: ${outcome.message}") }
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
                restore(fleetId)
                mutable.update { it.copy(message = "${source.displayName}: cancelled. What was downloaded was deleted.") }
            }
        }
    }

    /** Remembers which version the owner picked from an app's menu. Null goes back to "latest by the default rule". */
    fun selectRelease(fleetId: String, tag: String?) {
        mutable.update { s ->
            s.copy(selectedTagById = if (tag == null) s.selectedTagById - fleetId else s.selectedTagById + (fleetId to tag))
        }
    }

    /**
     * Downloads the version the owner picked for [fleetId] (or the default latest when none is picked). The APK still goes
     * through the same repo-pinned download, checksum when GitHub gives one, and Android's own confirm screen. An older
     * version than the installed one is allowed to be tried; Android decides, and its refusal is shown.
     */
    fun installSelected(fleetId: String) {
        val tag = mutable.value.selectedTagById[fleetId] ?: return install(fleetId)
        val source = FleetSources.forId(fleetId) ?: return
        val option = mutable.value.optionsById[fleetId]?.firstOrNull { it.tag == tag }
        val asset = option?.asset
        if (option == null || asset == null) {
            mutable.update { it.copy(message = "${source.displayName} $tag has no APK for this phone.") }
            return
        }
        if (!installer.canInstall()) {
            runCatching { context.startActivity(installer.unknownSourcesIntent()) }
            mutable.update { it.copy(message = "Allow DroidCommand to install apps, then tap Download again.") }
            return
        }
        if (stopFlags.containsKey(fleetId)) return
        setBusy(fleetId, "Downloading ${asset.name}…") // Stop is offered once the download itself has registered its stop flag
        viewModelScope.launch(Dispatchers.IO) {
            val outcome = downloadWithControls(source, asset)
            restore(fleetId)
            val downgrade = if (option.isDowngrade && outcome.handedOver) {
                " This is older than the installed version: if Android refuses it, uninstall the newer one first (its data is lost), then tap Download again."
            } else {
                ""
            }
            mutable.update { it.copy(message = "${source.displayName} $tag: ${outcome.message}$downgrade") }
        }
    }

    /** Sets this app's auto-update mode. It only ever updates an app that is already installed; it never first-installs or pairs. */
    fun setAutoUpdateMode(fleetId: String, mode: FleetAutoUpdateMode) {
        FleetAutoUpdate.setMode(context, fleetId, mode)
        publishAutoModes()
        val needsInstallRight = mode != FleetAutoUpdateMode.OFF && !installer.canInstall()
        mutable.update {
            it.copy(
                message = if (needsInstallRight) {
                    "Also allow DroidCommand to install apps in Android settings, or updates will wait."
                } else {
                    it.message
                },
            )
        }
        if (needsInstallRight) runCatching { context.startActivity(installer.unknownSourcesIntent()) }
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
                // One app's Stop or Cancel ends only that app; the rest of the queue carries on.
                val outcome = downloadWithControls(source, asset)
                if (outcome.handedOver) {
                    handedOver++
                    mutable.update { s ->
                        s.copy(byId = s.byId + (source.fleetId to FleetInstallUi(note = "Handed to Android. Confirm the install when it asks.")))
                    }
                } else {
                    failed += "${source.displayName} (${outcome.message.trimEnd('.')})"
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

    /** Turns pre-release downloads on or off for the default "latest" choice in this list. The version menus always list everything. */
    fun setIncludePrereleases(on: Boolean) {
        FleetPrereleasePref.setEnabled(context, on)
        mutable.update { it.copy(includePrereleases = on) }
        // The release lists are already here; only the default "latest" choice changes, so no new GitHub calls.
        viewModelScope.launch(Dispatchers.IO) {
            if (releases.isEmpty() && failedLookups.isEmpty()) refresh(force = true) else publishDecisions()
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
        publishAutoModes()
        if (on && !installer.canInstall()) runCatching { context.startActivity(installer.unknownSourcesIntent()) }
    }

    /** Back to what the stored look-up says, without another network call. */
    private fun restore(fleetId: String) {
        val source = FleetSources.forId(fleetId) ?: return
        val decision = decisions[fleetId]
        if (decision == null) {
            publishDecisions()
            return
        }
        finish(fleetId, describe(decision, installer.isInstalled(source.packageName)).withPartial(source, assetOf(decision)), null)
    }

    private fun assetOf(decision: UpdateDecision): ReleaseAsset? = when (decision) {
        is UpdateDecision.Install -> decision.asset
        is UpdateDecision.Update -> decision.asset
        else -> null
    }

    /** The result of one controlled download. [message] is the owner-facing line; [handedOver] is true only when Android has the APK. */
    private data class DownloadOutcome(val handedOver: Boolean, val message: String)

    /**
     * Downloads [asset] for [source] with Stop, Cancel and Resume, checks it and gives it to Android's installer. Blocking: call
     * off the main thread. Stop keeps the partial file (the next call for the same asset resumes it); Cancel deletes it. A
     * partial that belongs to a different release is discarded by the downloader, never appended to. Never throws.
     */
    private fun downloadWithControls(source: FleetAppSource, asset: ReleaseAsset): DownloadOutcome {
        val fleetId = source.fleetId
        val stop = AtomicBoolean(false)
        if (stopFlags.putIfAbsent(fleetId, stop) != null) return DownloadOutcome(false, "a download is already running.")
        try {
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
                apk
            }
            // The transfer has returned, so nothing is writing the partial file: a Cancel can delete it now.
            val discard = discardRequests.remove(fleetId)
            if (discard) installer.discardPartial(source)
            val failure = result.exceptionOrNull()
            return when {
                discard -> DownloadOutcome(false, "cancelled. What was downloaded was deleted.")
                failure is ApkDownloadStopped -> DownloadOutcome(false, "stopped. Resume continues from where it stopped.")
                failure != null -> DownloadOutcome(false, failure.message ?: "install failed")
                result.getOrThrow().digestChecked -> DownloadOutcome(true, "Downloaded and checked. Confirm in Android's installer.")
                else -> DownloadOutcome(
                    true,
                    "Downloaded. GitHub gave no checksum, so Android's signature check is the only one. Confirm in Android's installer.",
                )
            }
        } finally {
            stopFlags.remove(fleetId)
        }
    }

    /** Rebuilds decisions, version menus, per-card notes and the download list from the stored release lists. No network. */
    private fun publishDecisions() {
        val pre = mutable.value.includePrereleases
        val abis = Build.SUPPORTED_ABIS.toList()
        val installed = FleetSources.all.filter { installer.isInstalled(it.packageName) }.map { it.fleetId }.toSet()
        val options = HashMap<String, List<FleetReleaseOption>>()
        FleetSources.all.forEach { source ->
            val id = source.fleetId
            if (id in failedLookups) {
                decisions[id] = UpdateDecision.NothingPublished("Could not reach GitHub.")
                options[id] = emptyList()
            } else {
                val list = releases[id].orEmpty()
                decisions[id] = installer.decide(source, list, pre)
                options[id] = FleetReleaseOptions.build(source, installer.installedVersion(source.packageName), id in installed, list, abis)
            }
        }
        val next = FleetSources.all.mapNotNull { source ->
            decisions[source.fleetId]?.let { d ->
                source.fleetId to describe(d, source.fleetId in installed).withPartial(source, assetOf(d))
            }
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
                optionsById = options,
                lookupFailedIds = failedLookups.toSet(),
                // A picked tag that is no longer in the menu (release deleted) goes back to the default.
                selectedTagById = state.selectedTagById.filter { (id, tag) -> options[id].orEmpty().any { it.tag == tag } },
            )
        }
    }

    private fun publishAutoModes() {
        val modes = FleetSources.all.associate { it.fleetId to FleetAutoUpdate.effectiveMode(context, it.fleetId) }
        mutable.update { it.copy(autoModeById = modes, autoUpdate = FleetAutoUpdate.isEnabled(context)) }
    }

    /** Every app the list shows, in order: the Fleet roster, then the display-only extras, then any source left over. */
    private fun downloadDirectory(): List<Pair<String, String>> {
        val ordered = FleetCatalog.all.map { it.id to it.name } + FleetExtraApps.all.map { it.id to it.name }
        val known = ordered.map { it.first }.toSet()
        return ordered + FleetSources.all.filter { it.fleetId !in known }.map { it.fleetId to it.displayName }
    }

    private fun setBusy(id: String, note: String, canStop: Boolean = false) =
        mutable.update { it.copy(byId = it.byId + (id to FleetInstallUi(note = note, busy = true, canStop = canStop))) }

    /**
     * A kept partial file. The card's Resume applies only when the partial belongs to [defaultAsset], the one its button would
     * download; a partial of another release is still shown through [FleetInstallUi.kept] so a picked version can resume it.
     */
    private fun FleetInstallUi.withPartial(source: FleetAppSource, defaultAsset: ReleaseAsset?): FleetInstallUi {
        val bytes = installer.partialBytes(source)
        if (bytes <= 0L) return this
        val url = installer.partialSource(source)
        val withKept = copy(kept = KeptDownload(bytes, url))
        return if (url != null && url == defaultAsset?.url && actionLabel != null) {
            withKept.copy(partialBytes = bytes, note = "Stopped at ${megabytes(bytes)}. " + note.orEmpty())
        } else {
            withKept
        }
    }

    private fun megabytes(bytes: Long): String = "%.1f MB".format(bytes / (1024.0 * 1024.0))

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
        const val PROGRESS_INTERVAL_NANOS = 250_000_000L

        /** Do not look GitHub up again within this long unless forced. about 20 unauthenticated calls per look-up add up fast. */
        const val MIN_LOOKUP_GAP_MS = 10 * 60 * 1000L
    }
}
