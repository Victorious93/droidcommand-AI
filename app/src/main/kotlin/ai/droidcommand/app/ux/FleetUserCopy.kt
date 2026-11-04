package ai.droidcommand.app.ux

import ai.droidcommand.remotecontrol.FleetDashboardRow
import ai.droidcommand.remotecontrol.FleetDashboardTone
import ai.droidcommand.remotecontrol.FleetLiveStatusMapping

/**
 * Owner-facing fleet card. Maps the existing dashboard row + install probe
 * onto four statuses. Does not change pairing, cert checks, or defaults.
 */
enum class FleetUserKind {
    CONNECTED,
    INSTALLED_TAP_TO_PAIR,
    NOT_INSTALLED,
    BLOCKED,
}

data class FleetUserStatus(
    val kind: FleetUserKind,
    val label: String,
    val detail: String? = null,
    val fix: String? = null,
)

data class FleetCardUi(
    val id: String,
    val name: String,
    val role: String,
    val help: String,
    val status: FleetUserStatus,
    val seenLabel: String,
    val canPair: Boolean,
    val canUnpair: Boolean,
    val canTest: Boolean,
    val highlighted: Boolean,
    val pairSteps: List<String>,
    val opensApprovedCommands: Boolean,
)

object FleetUserCopy {
    val PAIR_STEPS = listOf(
        "Install the other app from its official source, if it is not on this phone yet.",
        "Open that app once so it can finish its own setup.",
        "Come back here and tap Pair.",
        "Confirm you trust this install. Pairing does not turn on remote control and does not run a command.",
    )

    val COMPANION_STEPS = listOf(
        "Install the other app from its official source.",
        "Open it once.",
        "Come back here and tap Test connection.",
        "These apps only talk when both were signed the same way. If Test fails, install matching builds.",
    )

    val TERMUX_STEPS = listOf(
        "Install Termux or VictorSuite (they share the same package name).",
        "Open it once and allow it to finish setup.",
        "Tap Test connection. Pairing is not used for this app.",
        "Linux commands still ask you before they run.",
    )

    fun status(
        installed: Boolean,
        paired: Boolean,
        tone: FleetDashboardTone,
        reason: String,
        pendingRepackage: Boolean = false,
    ): FleetUserStatus {
        val blocked = blockFor(reason)
        if (blocked != null) return blocked
        if (!installed && !paired) {
            return FleetUserStatus(
                kind = FleetUserKind.NOT_INSTALLED,
                label = "Not installed",
                detail = "Install this app, open it once, then pair.",
                fix = "Get it from its official source, then return here.",
            )
        }
        if (installed && !paired) {
            val detail = if (pendingRepackage) {
                "Installed. Uses the Termux name until that app is renamed. Tap Pair is not needed — use Test connection."
            } else {
                "Installed on this phone. Pair to tell DroidCommand you trust this install."
            }
            return FleetUserStatus(
                kind = FleetUserKind.INSTALLED_TAP_TO_PAIR,
                label = "Installed, tap to pair",
                detail = detail,
                fix = "Tap Pair and follow the steps. Nothing is sent until you confirm.",
            )
        }
        return when (tone) {
            FleetDashboardTone.OK -> FleetUserStatus(
                kind = FleetUserKind.CONNECTED,
                label = "Connected",
                detail = "Last check looked good.",
            )
            FleetDashboardTone.DEGRADED -> FleetUserStatus(
                kind = FleetUserKind.CONNECTED,
                label = "Connected",
                detail = "Connected, but the last check said this app is struggling.",
                fix = "Open the other app, then tap Test connection.",
            )
            FleetDashboardTone.DOWN -> FleetUserStatus(
                kind = FleetUserKind.BLOCKED,
                label = "Blocked: not reachable",
                detail = "DroidCommand could not reach this app just now.",
                fix = "Open the other app and tap Test connection. If it was replaced, unpair and pair again.",
            )
            FleetDashboardTone.UNKNOWN -> FleetUserStatus(
                kind = FleetUserKind.CONNECTED,
                label = "Connected",
                detail = if (paired) "Paired. Tap Test connection to check it." else null,
            )
        }
    }

    fun fromRow(
        row: FleetDashboardRow,
        installed: Boolean,
        opensApprovedCommands: Boolean = false,
    ): FleetCardUi {
        val pendingRepackage = row.member.pendingRepackage
        val user = status(
            installed = installed,
            paired = row.paired,
            tone = row.tone,
            reason = row.reason,
            pendingRepackage = pendingRepackage,
        )
        val canPair = row.member.opensPairing && installed && user.kind != FleetUserKind.BLOCKED
        val canTest = installed || row.paired
        val steps = when {
            row.member.id == "termux" || row.member.id == "victorsuite" -> TERMUX_STEPS
            opensApprovedCommands -> COMPANION_STEPS
            else -> PAIR_STEPS
        }
        return FleetCardUi(
            id = row.member.id,
            name = row.member.name,
            role = row.member.role,
            help = FleetAppHelp.forId(row.member.id),
            status = user,
            seenLabel = friendlySeen(row.seenLabel),
            canPair = canPair,
            canUnpair = row.paired && row.member.opensPairing,
            canTest = canTest,
            highlighted = row.member.highlighted,
            pairSteps = steps,
            opensApprovedCommands = opensApprovedCommands,
        )
    }

    fun banner(liveBindReady: Boolean, anyConnected: Boolean): String = when {
        !liveBindReady -> "Checking which of your apps are on this phone…"
        anyConnected -> "These are your other Victorious apps. Pair one to use it from here."
        else -> "None of your other apps are connected yet. Install one, then tap Pair."
    }

    fun pairConfirmTitle(name: String) = "Pair $name?"

    fun pairConfirmBody(name: String) =
        "This tells DroidCommand to trust the $name install that is on this phone now. " +
            "If that app is later replaced by a different install, the connection stops until you pair again. " +
            "This does not turn on remote control and does not run a command."

    fun unpairConfirmTitle(name: String) = "Stop pairing $name?"

    fun unpairConfirmBody(name: String) =
        "DroidCommand will stop talking to $name until you pair it again. The other app stays on the phone."

    private fun blockFor(reason: String): FleetUserStatus? {
        val lower = reason.lowercase()
        return when {
            reason == FleetLiveStatusMapping.REASON_SAME_SIGNING || looksSameSigning(lower) ->
                FleetUserStatus(
                    kind = FleetUserKind.BLOCKED,
                    label = "Blocked: apps were not signed the same way",
                    detail = "This phone will not let the two apps talk.",
                    fix = "Install matching builds of both apps, then tap Test connection.",
                )
            reason == FleetLiveStatusMapping.REASON_BIND_REFUSED_CERT || looksCert(lower) ->
                FleetUserStatus(
                    kind = FleetUserKind.BLOCKED,
                    label = "Blocked: this install does not match the trusted one",
                    detail = "The app on the phone is not the install you paired before.",
                    fix = "Unpair, then pair again only if you trust the app that is installed now.",
                )
            reason == FleetLiveStatusMapping.REASON_BIND_REFUSED_SIGNATURE || looksSignature(lower) ->
                FleetUserStatus(
                    kind = FleetUserKind.BLOCKED,
                    label = "Blocked: the other app refused this install",
                    detail = "The other app did not accept DroidCommand.",
                    fix = "Reinstall both from the same official source, then tap Test connection.",
                )
            reason == FleetLiveStatusMapping.REASON_PEER_CONTROL_OFF || looksPeerOff(lower) ->
                FleetUserStatus(
                    kind = FleetUserKind.BLOCKED,
                    label = "Blocked: remote control is off in the other app",
                    detail = "DroidCommand can see the app, but that app is not accepting remote commands.",
                    fix = "Open the other app and turn remote control on there. It stays off here unless you turn it on in Settings.",
                )
            reason == FleetLiveStatusMapping.REASON_BRIDGE_MISSING || looksBridge(lower) ->
                FleetUserStatus(
                    kind = FleetUserKind.BLOCKED,
                    label = "Blocked: this version cannot connect",
                    detail = "The installed build does not offer a connection.",
                    fix = "Update the other app, then tap Test connection.",
                )
            else -> null
        }
    }

    private fun friendlySeen(seen: String): String = when (seen) {
        "No snapshot yet" -> "Not checked yet"
        else -> seen.replace(" · stale", " · last check is old")
    }

    private fun looksSameSigning(lower: String) =
        lower.contains("same-signing") || lower.contains("same signing")

    private fun looksCert(lower: String) =
        (lower.contains("cert") || lower.contains("signer")) &&
            (lower.contains("mismatch") || lower.contains("refus") || lower.contains("not the paired"))

    private fun looksSignature(lower: String) =
        lower.contains("signature") && (lower.contains("refus") || lower.contains("denied") || lower.contains("mismatch"))

    private fun looksPeerOff(lower: String) =
        (lower.contains("external control") || lower.contains("peer control") || lower.contains("remote control")) &&
            (lower.contains("off") || lower.contains("disabled"))

    private fun looksBridge(lower: String) =
        lower.contains("bridge missing") || lower.contains("exposes no service") || lower.contains("no service for")
}

object FleetAppHelp {
    fun forId(id: String): String = when (id) {
        "hackerai" -> "Extra AI helpers for harder jobs. Connects when both apps were signed the same way."
        "pentestswarm" -> "Runs security tests you approve, one at a time. Nothing starts until you confirm."
        "rootforge" -> "Talks to a rooted phone and its gadgets. Pair it first. Flashing and wipes still ask you."
        "termux", "victorsuite" -> "Linux tools on this phone (Termux or VictorSuite). Install it, then tap Test connection."
        "edl-commander" -> "Talks to a phone that is in EDL (repair) mode. Not connected automatically."
        "kyc-auditlab" -> "ID-check helper that stays on this phone. Pair only if you installed that app."
        "file-manager" -> "Files on this phone. Pair so DroidCommand can ask it questions you approve."
        "keyboard" -> "Your custom keyboard. Pairing never reads what you type."
        "mesh" -> "A private mesh network. Pair to see whether it is up. Remote control stays off."
        "browser" -> "A private browser. Pair to see whether it is up. Remote control stays off."
        "victorsms" -> "Can deliver an approved command by text. Pairing does not send any message."
        "victorspoof" -> "Prank caller ID. Isolated from anything that can wipe or lock a phone."
        "victorious-flash" -> "Flashing and gadget tools. Anything that erases a device asks twice."
        "victorlaunch" -> "A launcher that can show this fleet list. Pair to share status with it."
        else -> "Another app in your set. Pair it only if you installed it and trust this copy."
    }
}
