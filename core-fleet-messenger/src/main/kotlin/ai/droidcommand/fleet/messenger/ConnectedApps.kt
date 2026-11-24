package ai.droidcommand.fleet.messenger

/**
 * Plain, JVM-testable rules behind the "Connected apps" list (Advanced). The list replaces a wall of always-off
 * "later phase" switches with one row per fleet app, each in exactly one honest state.
 *
 * Honesty rules encoded here, not left to the UI:
 * - [ConnectedAppStatus.ACTIVE] only when the node is trusted AND its last live call succeeded. A trusted node
 *   that was never called is [ConnectedAppStatus.PAIRED], never active.
 * - A paired node whose signer changed, or that vanished, is [ConnectedAppStatus.NEEDS_ATTENTION].
 * - Apps that are not paired by design (Termux / VictorSuite) report [ConnectedAppStatus.INSTALLED], not PAIRED.
 * - Nothing here turns anything on. Switching a feature on stays a separate, confirmed action.
 */
enum class ConnectedAppStatus(val label: String) {
    NOT_INSTALLED("Not installed"),
    NOT_PAIRED("Installed, not paired"),
    PAIRED("Paired"),
    ACTIVE("Active"),
    NEEDS_ATTENTION("Needs attention"),

    /** Installed, and this app type has no pairing step (Termux / VictorSuite). */
    INSTALLED("Installed"),
}

/** What the app learned about one fleet app. Built from package probes, [NodePairingState] and live calls. */
data class ConnectedAppFacts(
    val installed: Boolean,
    /** Null when this app type has no pairing step. */
    val pairing: NodePairingState?,
    /** Null = no live call made yet. True = last call succeeded. False = last call failed or timed out. */
    val lastLiveCallOk: Boolean?,
)

data class ConnectedAppResolution(val status: ConnectedAppStatus, val detail: String)

object ConnectedAppStatusResolver {
    fun resolve(facts: ConnectedAppFacts): ConnectedAppResolution {
        if (facts.pairing == null) {
            return if (facts.installed) {
                ConnectedAppResolution(ConnectedAppStatus.INSTALLED, "Installed. No pairing step for this app.")
            } else {
                ConnectedAppResolution(ConnectedAppStatus.NOT_INSTALLED, "Not found on this phone.")
            }
        }
        return when (facts.pairing) {
            NodePairingState.NOT_INSTALLED ->
                ConnectedAppResolution(ConnectedAppStatus.NOT_INSTALLED, "Not found on this phone.")
            NodePairingState.NOT_PAIRED ->
                ConnectedAppResolution(ConnectedAppStatus.NOT_PAIRED, "Installed. Pair it to let DroidCommand ask it questions.")
            NodePairingState.TRUSTED -> when (facts.lastLiveCallOk) {
                true -> ConnectedAppResolution(ConnectedAppStatus.ACTIVE, "Answered the last check.")
                false -> ConnectedAppResolution(ConnectedAppStatus.NEEDS_ATTENTION, "Paired, but the last check failed.")
                null -> ConnectedAppResolution(ConnectedAppStatus.PAIRED, "Paired. Not checked yet.")
            }
            NodePairingState.CERT_MISMATCH ->
                ConnectedAppResolution(ConnectedAppStatus.NEEDS_ATTENTION, "The app's signature changed since you paired it. Unpair and check before trusting it again.")
            NodePairingState.UNTRUSTED ->
                ConnectedAppResolution(ConnectedAppStatus.NEEDS_ATTENTION, "Paired, but the app is missing or could not be verified.")
            null -> error("unreachable: pairing null handled above")
        }
    }
}

/**
 * What a fleet app is for, in owner language, and which old always-off assistant switches it replaces.
 * [catalogId] matches both the `:app` fleet catalog and [ExternalNodeCatalog] ids.
 */
data class ConnectedAppPurpose(
    val catalogId: String,
    val title: String,
    val whatItDoes: String,
    /** `AssistantCapability.wireName` values this app takes over. Kept as strings: this module has no config dependency. */
    val replacesGates: List<String>,
    /** True for apps that are paired through Messenger. False for Termux / VictorSuite. */
    val pairs: Boolean = true,
)

object ConnectedAppPurposes {
    val all: List<ConnectedAppPurpose> = listOf(
        ConnectedAppPurpose(
            catalogId = "victorsms",
            title = "Messages",
            whatItDoes = "Texts go through VictorSMS, and it asks you before every one. Email joins here only if VictorSMS adds it.",
            replacesGates = listOf("smsAccessEnabled", "mailboxAccessEnabled"),
        ),
        ConnectedAppPurpose(
            catalogId = "termux",
            title = "Linux environment",
            whatItDoes = "Runs Linux commands inside VictorSuite (Termux). DroidCommand does not carry its own Linux.",
            replacesGates = listOf("linuxEnvironmentEnabled"),
            pairs = false,
        ),
        ConnectedAppPurpose(
            catalogId = "file-manager",
            title = "Files and cloud transfer",
            whatItDoes = "Lists and moves files through Victorious File Manager.",
            replacesGates = listOf("cloudTransferEnabled"),
        ),
        ConnectedAppPurpose(
            catalogId = "mesh",
            title = "Private network",
            whatItDoes = "Reaches your PC and other devices over Victorious Mesh.",
            replacesGates = emptyList(),
        ),
        ConnectedAppPurpose(
            catalogId = "browser",
            title = "Browser",
            whatItDoes = "Opens links and shows browsing results in Victorious Browser.",
            replacesGates = emptyList(),
        ),
    )

    /** Gates that no connected app takes over and that still have no implementation. They are hidden, not deleted. */
    val gatesWithoutAHome: Set<String> = setOf("backgroundAutomationEnabled", "generatedUiEnabled", "kaiBuildEnabled")
}

/** One row of the list, ready to draw. */
data class ConnectedAppRow(
    val purpose: ConnectedAppPurpose,
    val status: ConnectedAppStatus,
    val detail: String,
)

object ConnectedApps {
    /** Builds the rows in the order of [ConnectedAppPurposes.all]. [facts] is keyed by catalog id; a missing key means "not installed". */
    fun rows(facts: Map<String, ConnectedAppFacts>): List<ConnectedAppRow> =
        ConnectedAppPurposes.all.map { purpose ->
            val known = facts[purpose.catalogId]
                ?: ConnectedAppFacts(installed = false, pairing = if (purpose.pairs) NodePairingState.NOT_INSTALLED else null, lastLiveCallOk = null)
            val shaped = if (purpose.pairs) known else known.copy(pairing = null)
            val resolved = ConnectedAppStatusResolver.resolve(shaped)
            ConnectedAppRow(purpose, resolved.status, resolved.detail)
        }
}
