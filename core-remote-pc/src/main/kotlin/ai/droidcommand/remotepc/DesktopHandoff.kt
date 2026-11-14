package ai.droidcommand.remotepc

/**
 * PC desktop access through the EXISTING rustdeskV Android app (the Victorious RustDesk fork). Nothing here embeds a desktop
 * engine, relay, or protocol. DroidCommand only builds a validated, explicit hand-off to the installed app; the session
 * itself (rendezvous, keys, self-hosted server settings, passwords) stays inside rustdeskV.
 *
 * Verified from rustdeskV source (not from a README): `flutter/android/app/src/main/AndroidManifest.xml` declares the exported
 * `MainActivity` of package `com.carriez.flutter_hbb` with `ACTION_VIEW` on scheme `rustdesk`; `flutter/lib/common.dart`
 * `handleUriLink` treats `rustdesk://<peer-id>` as "connect to this peer" and ignores `rustdesk://config` and a `password`
 * query unless the owner enabled the matching app option. So the contract is `VIEW rustdesk://<id>` — nothing else is sent.
 *
 * Three identities are kept apart on purpose and never share a field: the VictorSuite SSH profile (host key), the RustDesk peer
 * id (RustDesk's own key exchange), and the inference server. An SSH host-key fingerprint is NOT a RustDesk identity, and a PC
 * answering on its SSH port says nothing about whether a desktop session will start.
 */
object RustDeskContract {
    const val PACKAGE = "com.carriez.flutter_hbb"
    const val SCHEME = "rustdesk"
    const val ACTION_VIEW = "android.intent.action.VIEW"

    /** Build number (`+71` in flutter/pubspec.yaml 1.5.3) the deep-link contract above was read from. Older builds are refused until re-verified. */
    const val MIN_VERIFIED_VERSION_CODE = 71L
}

/** RustDesk's own rule (`is_valid_untrusted_peer_id`, rustdeskV src/common.rs) PLUS the extra restriction needed to put an id in a URI authority. */
object RustDeskPeerId {
    private const val MAX_LEN = 253 // same cap as rustdeskV
    private val RUSTDESK_FORBIDDEN = setOf('"', '<', '>', '/', '\\', '|', '?', '*')
    private val URI_SAFE = Regex("^[A-Za-z0-9._@:-]+$") // stricter: nothing that can add path/query/fragment/userinfo tricks

    sealed class Result {
        data class Ok(val id: String) : Result()
        data class Rejected(val reason: String) : Result()
    }

    fun parse(raw: String?): Result {
        val id = raw?.trim().orEmpty()
        if (id.isEmpty()) return Result.Rejected("The RustDesk ID is empty.")
        if (id.length > MAX_LEN) return Result.Rejected("The RustDesk ID is too long.")
        if (id.any { it.isISOControl() || it.isWhitespace() || it in RUSTDESK_FORBIDDEN }) return Result.Rejected("The RustDesk ID has characters RustDesk does not allow.")
        if (!URI_SAFE.matches(id)) return Result.Rejected("DroidCommand can only hand off IDs made of letters, digits and . _ @ : - (found something else).")
        return Result.Ok(id)
    }
}

/** Owner-entered association: one VictorSuite PC profile ↔ one RustDesk peer. Holds no password and no key. */
data class DesktopLink(val profileId: String, val peerId: String) {
    override fun toString() = "DesktopLink(profile=$profileId, peer=<hidden>)"
}

/** What PackageManager reported about the installed app. Built by :app; pure data here. */
data class InstalledDesktopApp(val packageName: String, val versionCode: Long, val versionName: String?, val signingCertSha256: Set<String>)

/** Certificates the owner has chosen to trust for the desktop app (first-use confirmation, then pinned). Never auto-trusted. */
fun interface DesktopTrustStore {
    fun pinnedCertSha256(packageName: String): Set<String>
}

/** What the app should do next. A [Ready] plan is the ONLY thing that may be turned into an Intent. */
sealed class DesktopLaunchPlan {
    /** Explicit intent: action VIEW, data [uri], `setPackage` [targetPackage]. Never carries a password, key, server or clipboard. */
    data class Ready(val action: String, val uri: String, val targetPackage: String) : DesktopLaunchPlan() {
        override fun toString() = "Ready(action=$action, uri=$scheme://<hidden>, package=$targetPackage)"
        private val scheme get() = uri.substringBefore("://")
    }

    object NotInstalled : DesktopLaunchPlan()
    data class UnsupportedVersion(val installed: Long, val required: Long) : DesktopLaunchPlan()

    /** Installed app's signing certificate is not one the owner trusts yet; show it and ask once. */
    data class NeedsOwnerTrust(val certSha256: Set<String>) : DesktopLaunchPlan()

    /** Installed app's certificate differs from the pinned one: refuse (a repackaged or different app). */
    data class IdentityMismatch(val pinned: Set<String>, val found: Set<String>) : DesktopLaunchPlan()
    data class MalformedId(val reason: String) : DesktopLaunchPlan()
    data class WrongPackage(val found: String) : DesktopLaunchPlan()
}

object DesktopHandoff {
    fun plan(
        link: DesktopLink,
        installed: InstalledDesktopApp?,
        trust: DesktopTrustStore,
        minVersionCode: Long = RustDeskContract.MIN_VERIFIED_VERSION_CODE,
    ): DesktopLaunchPlan {
        val id = when (val r = RustDeskPeerId.parse(link.peerId)) {
            is RustDeskPeerId.Result.Ok -> r.id
            is RustDeskPeerId.Result.Rejected -> return DesktopLaunchPlan.MalformedId(r.reason)
        }
        if (installed == null) return DesktopLaunchPlan.NotInstalled
        if (installed.packageName != RustDeskContract.PACKAGE) return DesktopLaunchPlan.WrongPackage(installed.packageName)
        if (installed.versionCode < minVersionCode) return DesktopLaunchPlan.UnsupportedVersion(installed.versionCode, minVersionCode)
        val found = installed.signingCertSha256.map { it.uppercase() }.toSet()
        if (found.isEmpty()) return DesktopLaunchPlan.IdentityMismatch(emptySet(), emptySet()) // unreadable certificate is never trusted
        val pinned = trust.pinnedCertSha256(installed.packageName).map { it.uppercase() }.toSet()
        if (pinned.isEmpty()) return DesktopLaunchPlan.NeedsOwnerTrust(found)
        if (found.intersect(pinned).isEmpty()) return DesktopLaunchPlan.IdentityMismatch(pinned, found)
        return DesktopLaunchPlan.Ready(RustDeskContract.ACTION_VIEW, "${RustDeskContract.SCHEME}://$id", RustDeskContract.PACKAGE)
    }

    /** Plain-words text for the owner. Never includes the peer id, a password or a key. */
    fun message(plan: DesktopLaunchPlan, pcName: String): String? = when (plan) {
        is DesktopLaunchPlan.Ready -> null
        DesktopLaunchPlan.NotInstalled -> "rustdeskV is not installed. Install it, then try again."
        is DesktopLaunchPlan.UnsupportedVersion -> "This rustdeskV is too old to be opened from DroidCommand (needs build ${plan.required} or newer). Update rustdeskV."
        is DesktopLaunchPlan.NeedsOwnerTrust -> "Confirm that the installed rustdeskV is yours before DroidCommand opens $pcName with it."
        is DesktopLaunchPlan.IdentityMismatch -> "The installed rustdeskV is signed differently from the one you trusted. DroidCommand did not open $pcName."
        is DesktopLaunchPlan.MalformedId -> "The RustDesk ID saved for $pcName is not valid: ${plan.reason}"
        is DesktopLaunchPlan.WrongPackage -> "That app is not rustdeskV, so DroidCommand did not open $pcName."
    }

    /**
     * Desktop-session availability is NOT inferable from SSH reachability or inference-server status. DroidCommand only knows it
     * handed off; whether the PC answered, was offline, or the owner cancelled is reported by rustdeskV itself.
     */
    enum class SessionKnowledge { NOT_ATTEMPTED, HANDED_OFF_RESULT_UNKNOWN }
}
