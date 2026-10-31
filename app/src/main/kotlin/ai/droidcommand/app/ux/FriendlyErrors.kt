package ai.droidcommand.app.ux

/**
 * Turns a raw failure string into something a first-time owner can act on.
 * Does not hide the real cause: the original text is kept when we cannot
 * map it, and never replaced with a success.
 */
object FriendlyErrors {
    fun forUser(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return "Something went wrong. Try again."
        val lower = text.lowercase()
        return when {
            looksLikeMissingKey(lower) ->
                "This cloud provider has no key yet. Add one in Settings, or pick an on-device model on the Models tab."
            looksLikeMissingModel(lower) ->
                "No on-device model is selected. Open Models and pick a file or download one from the catalog."
            looksLikeNativeMissing(lower) ->
                "This phone build cannot run an on-device model yet. You can still use a cloud key in Settings."
            looksLikeNetwork(lower) ->
                "The network request did not finish. Check the connection and try again."
            looksLikeAuth(lower) ->
                "That cloud key was rejected. Open Settings, remove it, and save the key again."
            looksLikeNotInstalled(lower) ->
                "That app is not installed on this phone. Install it, open it once, then come back here."
            looksLikeNotPaired(lower) ->
                "This app is installed but not paired yet. Tap Pair and confirm you trust this install."
            looksLikeCert(lower) ->
                "Blocked: this install does not match the one you already trusted. Unpair, then pair again only if you trust the app that is on the phone now."
            looksLikeSameSigning(lower) ->
                "Blocked: these two apps were not signed the same way, so they cannot talk. Install matching builds, or use the official pair."
            looksLikePeerOff(lower) ->
                "Blocked: remote control is turned off in the other app. Open that app and turn it on there. It stays off here unless you turn it on in Settings."
            looksLikeBridge(lower) ->
                "Blocked: this version of the other app cannot connect. Update that app and tap Test connection."
            looksLikePermission(lower) ->
                "This phone did not grant that permission. You can try again, or skip — the rest of the app still works."
            looksLikeRefusedSend(lower) ->
                "Chat cannot send yet. Pick a model on the Models tab, or add a cloud key in Settings."
            else -> text
        }
    }

    fun missingChatSetup(providerLabel: String): String =
        "Chat needs a model on this phone or a $providerLabel key in Settings before it can answer."

    fun emptyChat(hasSetup: Boolean): String = if (hasSetup) {
        "Ask anything. Replies stay in this chat. Open Models to change the on-device model, or Settings to add a cloud key."
    } else {
        "Ask anything — after you pick a model or add a cloud key. Open the Models tab to pick a .gguf or browse the free catalog."
    }

    fun testNeedsInstall(): String = "Install the app first, open it once, then tap Test connection."

    fun testNeedsPair(): String = "Pair this app first, then tap Test connection."

    fun testBusy(): String = "Still checking…"

    fun testOk(): String = "Connection works."

    fun testFailed(detail: String?): String = "Could not reach the app. ${forUser(detail)}"

    private fun looksLikeMissingKey(lower: String) =
        (lower.contains("api key") || lower.contains("no key") || lower.contains("not set")) &&
            (lower.contains("refus") || lower.contains("missing") || lower.contains("without") || lower.contains("provider"))

    private fun looksLikeMissingModel(lower: String) =
        lower.contains("no model") || lower.contains("no on-device") || lower.contains("select a model") ||
            (lower.contains("model") && lower.contains("not selected"))

    private fun looksLikeNativeMissing(lower: String) =
        lower.contains("native") && (lower.contains("missing") || lower.contains("not loaded") || lower.contains("unsatisfied"))

    private fun looksLikeNetwork(lower: String) =
        lower.contains("timeout") || lower.contains("unable to resolve") || lower.contains("failed to connect") ||
            lower.contains("network") && (lower.contains("fail") || lower.contains("error") || lower.contains("unreachable"))

    private fun looksLikeAuth(lower: String) =
        lower.contains("unauthorized") || lower.contains("401") || lower.contains("invalid api key") ||
            lower.contains("authentication")

    private fun looksLikeNotInstalled(lower: String) =
        lower.contains("not installed") || lower.contains("not visible")

    private fun looksLikeNotPaired(lower: String) =
        lower.contains("not paired") || lower.contains("pair to connect")

    private fun looksLikeCert(lower: String) =
        (lower.contains("cert") || lower.contains("signer") || lower.contains("signing")) &&
            (lower.contains("mismatch") || lower.contains("refus") || lower.contains("not the paired") || lower.contains("expected"))

    private fun looksLikeSameSigning(lower: String) =
        lower.contains("same-signing") || lower.contains("same signing")

    private fun looksLikePeerOff(lower: String) =
        (lower.contains("external control") || lower.contains("peer control") || lower.contains("remote control")) &&
            (lower.contains("off") || lower.contains("disabled") || lower.contains("not enabled"))

    private fun looksLikeBridge(lower: String) =
        lower.contains("bridge missing") || lower.contains("exposes no service") ||
            lower.contains("no service for") || lower.contains("returned no binder")

    private fun looksLikePermission(lower: String) =
        lower.contains("permission") && (lower.contains("denied") || lower.contains("refus") || lower.contains("not granted"))

    private fun looksLikeRefusedSend(lower: String) =
        lower.contains("send is refused") || lower.contains("refuse a send") || lower.contains("refus") && lower.contains("until")
}
