package ai.droidcommand.app.ui.fleet

import ai.droidcommand.app.fleet.FleetCatalog
import ai.droidcommand.app.fleet.FleetExtraApps
import ai.droidcommand.app.fleet.FleetExtraCard
import ai.droidcommand.app.fleet.FleetExtraLink
import ai.droidcommand.app.fleet.install.FleetSources
import ai.droidcommand.app.ui.components.DroidCard
import ai.droidcommand.app.ui.components.SectionLabel
import ai.droidcommand.app.ui.theme.AccentSuccess
import ai.droidcommand.app.ui.theme.DroidPrimaryButton
import ai.droidcommand.app.ui.theme.DroidSecondaryButton
import ai.droidcommand.app.ui.theme.DroidTextButton
import ai.droidcommand.app.ui.theme.TextSecondary
import ai.droidcommand.app.update.ApkDownload
import ai.droidcommand.fleet.update.FleetAutoUpdateMode
import ai.droidcommand.fleet.update.FleetDownloadAction
import ai.droidcommand.fleet.update.FleetDownloadQueue
import ai.droidcommand.fleet.update.FleetDownloadRow
import ai.droidcommand.fleet.update.FleetReleaseOption
import ai.droidcommand.fleet.update.FleetReleaseOptions
import ai.droidcommand.fleet.update.FleetRepoLinks
import ai.droidcommand.fleet.update.FleetVersionRelation
import ai.droidcommand.fleet.update.UpdateDecision
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Package names an app is known by, for reading its real icon off the phone. Roster members include debug / bench aliases. */
private fun packagesFor(id: String): List<String> =
    FleetCatalog.all.firstOrNull { it.id == id }?.androidPackages
        ?: FleetExtraApps.forId(id)?.let { listOf(it.packageName) }
        ?: FleetSources.forId(id)?.let { listOf(it.packageName) }
        ?: emptyList()

/**
 * "Download your apps": one row per app of the owner's, each with its icon, a download button, a **version menu** (every
 * published release, whether stable, debug or pre-release), a per-app **auto-update** menu and an **Open repo** button, plus a
 * Download all button. A row's default button only offers a download when a real release APK was found; picking a version from
 * the menu downloads exactly that one. Every download is checked and then confirmed by the owner in Android's own installer.
 * Nothing here pairs an app.
 */
@Composable
fun FleetDownloadSection(
    install: FleetInstallState,
    onInstall: (String) -> Unit,
    onInstallAll: () -> Unit,
    onIncludePrereleases: (Boolean) -> Unit = {},
    onSelectVersion: (String, String?) -> Unit = { _, _ -> },
    onAutoMode: (String, FleetAutoUpdateMode) -> Unit = { _, _ -> },
    onStop: (String) -> Unit = {},
    onCancel: (String) -> Unit = {},
) {
    val rows = install.rows
    val queue = FleetDownloadQueue.queue(rows)
    DroidCard(modifier = Modifier.testTag("fleet_download_section")) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Download your apps", style = MaterialTheme.typography.titleMedium)
            Text(
                FleetDownloadQueue.summary(rows),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.testTag("fleet_download_summary"),
            )
            Text(
                "Pick any published version from an app's Version menu, or set the app to auto-update. Auto-update only updates " +
                    "an app that is already on this phone; it never installs a new one or pairs anything.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("fleet_download_prereleases")) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Include pre-releases by default", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Only changes what \"latest\" means and what Download all fetches. Version menus always list everything. " +
                            "Off by default. Android still refuses an update signed with a different key, and you confirm every install.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                }
                Switch(checked = install.includePrereleases, onCheckedChange = onIncludePrereleases)
            }
            DroidPrimaryButton(
                onClick = onInstallAll,
                enabled = queue.isNotEmpty() && !install.bulkBusy,
                modifier = Modifier.fillMaxWidth().testTag("fleet_download_all"),
            ) { Text(if (queue.isEmpty()) "Download all" else "Download all (${queue.size})") }
            if (install.bulkBusy) CircularProgressIndicator()
            rows.forEach { row ->
                DownloadRow(
                    row = row,
                    installed = row.fleetId in install.installedIds,
                    ui = install.byId[row.fleetId],
                    options = install.optionsById[row.fleetId].orEmpty(),
                    lookupFailed = row.fleetId in install.lookupFailedIds,
                    selectedTag = install.selectedTagById[row.fleetId],
                    autoMode = install.autoModeById[row.fleetId] ?: FleetAutoUpdateMode.OFF,
                    onInstall = { onInstall(row.fleetId) },
                    onSelectVersion = { onSelectVersion(row.fleetId, it) },
                    onAutoMode = { onAutoMode(row.fleetId, it) },
                    onStop = { onStop(row.fleetId) },
                    onCancel = { onCancel(row.fleetId) },
                    bulkBusy = install.bulkBusy,
                )
            }
        }
    }
}

@Composable
private fun DownloadRow(
    row: FleetDownloadRow,
    installed: Boolean,
    ui: FleetInstallUi?,
    options: List<FleetReleaseOption>,
    lookupFailed: Boolean,
    selectedTag: String?,
    autoMode: FleetAutoUpdateMode,
    onInstall: () -> Unit,
    onSelectVersion: (String?) -> Unit,
    onAutoMode: (FleetAutoUpdateMode) -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    bulkBusy: Boolean,
) {
    val context = LocalContext.current
    val busy = ui?.busy == true
    val picked = options.firstOrNull { it.tag == selectedTag }
    // A picked version is downloaded even when the default rule would offer nothing (for example only pre-releases exist).
    val canDownload = if (picked != null) picked.installable else row.canFetch
    // A kept partial resumes only for the exact asset this row would download now (the picked one, else the default). A partial of
    // another release is never resumed into it; the downloader discards it and starts clean.
    val defaultUrl = when (val d = row.decision) {
        is UpdateDecision.Install -> d.asset.url
        is UpdateDecision.Update -> d.asset.url
        else -> null
    }
    val targetUrl = picked?.asset?.url ?: defaultUrl
    val kept = ui?.kept
    val resumable = kept?.assetUrl != null && kept.assetUrl == targetUrl
    val label = when {
        resumable -> "Resume"
        picked != null -> if (picked.relation == FleetVersionRelation.SAME) "Reinstall" else "Download"
        else -> buttonLabel(row)
    }
    val repoUrl = FleetRepoLinks.url(FleetSources.repoFor(row.fleetId))
    Column(modifier = Modifier.fillMaxWidth().testTag("fleet_download_row_${row.fleetId}"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            FleetAppIcon(
                fleetId = row.fleetId,
                name = row.displayName,
                packageNames = packagesFor(row.fleetId),
                installed = installed,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(row.displayName, style = MaterialTheme.typography.titleSmall)
                Text(
                    statusLine(row, installed, ui),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (installed) AccentSuccess else TextSecondary,
                )
            }
            Spacer(Modifier.width(8.dp))
            if (canDownload) {
                DroidPrimaryButton(
                    onClick = onInstall,
                    enabled = !busy && !bulkBusy,
                    modifier = Modifier.width(ButtonWidth).testTag("fleet_download_${row.fleetId}"),
                ) { Text(label) }
            } else {
                DroidSecondaryButton(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.width(ButtonWidth).testTag("fleet_download_${row.fleetId}"),
                ) { Text(label) }
            }
        }
        // Downloading: Stop keeps what is downloaded, Cancel deletes it.
        if (ui?.canStop == true) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                DroidSecondaryButton(onClick = onStop, modifier = Modifier.weight(1f).testTag("fleet_download_stop_${row.fleetId}")) { Text("Stop") }
                DroidSecondaryButton(onClick = onCancel, modifier = Modifier.weight(1f).testTag("fleet_download_cancel_${row.fleetId}")) { Text("Cancel") }
            }
        } else if (kept != null && !busy && !bulkBusy) {
            // Stopped earlier. Resume is the main button when it is for this version; this deletes what was kept.
            DroidTextButton(onClick = onCancel, modifier = Modifier.testTag("fleet_download_discard_${row.fleetId}")) {
                Text("Delete the partial download (${"%.1f".format(kept.bytes / (1024.0 * 1024.0))} MB)")
            }
        }
        if (row.source != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                VersionMenu(
                    fleetId = row.fleetId,
                    options = options,
                    lookupFailed = lookupFailed,
                    selectedTag = selectedTag,
                    onSelect = onSelectVersion,
                    modifier = Modifier.weight(1f),
                )
                AutoUpdateMenu(
                    fleetId = row.fleetId,
                    mode = autoMode,
                    onSelect = onAutoMode,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        picked?.let { option ->
            if (option.isDowngrade) {
                Text(
                    "Older than the installed version. Android usually refuses that; if it does, uninstall the newer one first (its data is lost).",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
        }
        if (repoUrl != null) {
            DroidTextButton(
                onClick = { ApkDownload.openPage(context, repoUrl) },
                modifier = Modifier.testTag("fleet_repo_${row.fleetId}"),
            ) { Text("Open ${row.displayName} on GitHub") }
        }
    }
}

/** Dropdown of every published release of one app. An app with no releases says so and the button is disabled. */
@Composable
private fun VersionMenu(
    fleetId: String,
    options: List<FleetReleaseOption>,
    lookupFailed: Boolean,
    selectedTag: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val title = when {
        lookupFailed -> "Versions: can't reach GitHub"
        options.isEmpty() -> "Versions: none"
        selectedTag != null -> "Version: $selectedTag"
        else -> "Version: latest"
    }
    Box(modifier = modifier) {
        DroidSecondaryButton(
            onClick = { open = true },
            enabled = options.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().testTag("fleet_version_$fleetId"),
        ) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (options.isEmpty()) {
                DropdownMenuItem(text = { Text(FleetReleaseOptions.NONE_LABEL) }, enabled = false, onClick = { open = false })
            } else {
                DropdownMenuItem(
                    text = { Text("Latest (default rule)") },
                    onClick = {
                        open = false
                        onSelect(null)
                    },
                )
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        enabled = option.installable,
                        onClick = {
                            open = false
                            onSelect(option.tag)
                        },
                    )
                }
            }
        }
    }
}

/** Per-app auto-update: Off, stable releases only, or including pre-releases. Only updates an app already installed. */
@Composable
private fun AutoUpdateMenu(
    fleetId: String,
    mode: FleetAutoUpdateMode,
    onSelect: (FleetAutoUpdateMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        DroidSecondaryButton(
            onClick = { open = true },
            modifier = Modifier.fillMaxWidth().testTag("fleet_autoupdate_$fleetId"),
        ) {
            Text("Auto-update: ${mode.label}", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            FleetAutoUpdateMode.entries.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(if (choice == mode) "${choice.label} (current)" else choice.label) },
                    onClick = {
                        open = false
                        onSelect(choice)
                    },
                )
            }
        }
    }
}

private val ButtonWidth = 124.dp

private fun buttonLabel(row: FleetDownloadRow): String = when (row.action) {
    FleetDownloadAction.DOWNLOAD -> "Download"
    FleetDownloadAction.UPDATE -> "Update"
    FleetDownloadAction.UP_TO_DATE -> "Up to date"
    FleetDownloadAction.UNKNOWN -> "Installed"
    FleetDownloadAction.NOT_PUBLISHED -> "Not published"
    FleetDownloadAction.NO_SOURCE -> "No download"
}

private fun statusLine(row: FleetDownloadRow, installed: Boolean, ui: FleetInstallUi?): String {
    // A progress note from a download in flight wins over the generic line.
    if (ui?.busy == true) ui.note?.let { return it }
    return when (row.action) {
        FleetDownloadAction.DOWNLOAD -> ui?.note ?: "Not installed. A release is published."
        FleetDownloadAction.UPDATE -> ui?.note ?: "Installed. A newer release is published."
        FleetDownloadAction.UP_TO_DATE -> ui?.note ?: "Installed and up to date."
        FleetDownloadAction.UNKNOWN -> "Installed. Its version cannot be compared with the release."
        FleetDownloadAction.NOT_PUBLISHED ->
            if (installed) "Installed. No newer APK is published." else "Not installed. No APK is published yet."
        FleetDownloadAction.NO_SOURCE -> "No public download source is set up. Get it from its official source."
    }
}

/**
 * Apps of the owner's that are on the Fleet screen but are not control-plane nodes (see [FleetExtraApps]). Inbound apps show
 * how to link the installed copy; apps with no hub bridge say so instead of offering a pairing that does not exist.
 */
@Composable
fun FleetExtraAppsSection(
    extras: List<FleetExtraCard>,
    onOpenPasswordPairing: () -> Unit,
) {
    if (extras.isEmpty()) return
    var linkHelp by remember { mutableStateOf<FleetExtraCard?>(null) }
    SectionLabel("More of your apps")
    extras.forEach { card ->
        DroidCard(modifier = Modifier.testTag("fleet_extra_${card.app.id}")) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FleetAppIcon(
                        fleetId = card.app.id,
                        name = card.app.name,
                        packageNames = listOf(card.app.packageName),
                        installed = card.installed,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(card.app.name, style = MaterialTheme.typography.titleMedium)
                        Text(card.app.role, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    }
                }
                Text(
                    if (card.installed) "Installed" else "Not installed",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (card.installed) AccentSuccess else TextSecondary,
                )
                Text(card.app.about, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                when (card.app.link) {
                    FleetExtraLink.PASSWORD_LINK -> DroidPrimaryButton(
                        onClick = { linkHelp = card },
                        modifier = Modifier.fillMaxWidth().testTag("fleet_extra_link_${card.app.id}"),
                    ) { Text("Link in Settings") }
                    FleetExtraLink.NO_HUB_LINK -> Text(
                        "Nothing to pair: DroidCommand has no bridge to this app.",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary,
                    )
                }
            }
        }
    }
    linkHelp?.let { card ->
        AlertDialog(
            onDismissRequest = { linkHelp = null },
            title = { Text("Link ${card.app.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Package: ${card.app.packageName}")
                    Text("1. Install ${card.app.name} (use the download list above) and open it once.", style = MaterialTheme.typography.bodySmall)
                    Text("2. In Settings → Password pairing, turn pairing on and set a password.", style = MaterialTheme.typography.bodySmall)
                    Text("3. Type the package name above, then your password, and tap Link installed package.", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "Linking trusts only this install's signing certificate. It does not turn on remote control and does not run anything.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                DroidTextButton(onClick = {
                    linkHelp = null
                    onOpenPasswordPairing()
                }) { Text("Open Settings") }
            },
            dismissButton = { DroidTextButton(onClick = { linkHelp = null }) { Text("Cancel") } },
        )
    }
}
