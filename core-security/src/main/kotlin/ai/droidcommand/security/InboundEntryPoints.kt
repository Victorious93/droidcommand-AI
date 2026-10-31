package ai.droidcommand.security

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Inbound cert posture for a droidcommand-AI companion entry point.
 *
 * [ENFORCED] means [KnownNodeSigningCatalog] already has a non-empty pin
 * for that package and this inbound path uses it fail-closed.
 * [PENDING_REFUSED] means the cert is undocumented or catalogued as
 * unenforced — callers are refused. Do not fail open. Do not invent a
 * fingerprint.
 */
enum class InboundCertStatus {
    ENFORCED,
    PENDING_REFUSED,
}

/** How this row is used. [PATTERN] is File-Manager CODE_ASSIST (PR #255). */
enum class InboundEntryKind {
    INCLUDED,
    EXCLUDED,
    PATTERN,
}

/**
 * One inbound companion entry point. Pins are **never** stored here —
 * they are resolved from [KnownNodeSigningCatalog] / [KnownFleet] at
 * check time so this is not a second cert list.
 */
data class InboundEntryPoint(
    val id: String,
    val displayName: String,
    val packages: List<String>,
    val capability: String,
    val certStatus: InboundCertStatus,
    val kind: InboundEntryKind,
    val laterAddition: Boolean = false,
    val certTodo: String? = null,
    val packageSource: String,
    val notes: String,
) {
    init {
        require(id.matches(Regex("""^[a-z0-9][a-z0-9._-]*$"""))) { "Invalid inbound entry id: $id" }
        require(packages.isNotEmpty()) { "$id must name at least one package" }
        require(packages.all { it.isNotBlank() }) { "$id has a blank package" }
        require(capability.isNotBlank()) { "$id capability must not be blank" }
        if (kind == InboundEntryKind.EXCLUDED) {
            require(certStatus == InboundCertStatus.PENDING_REFUSED) {
                "$id is excluded and must stay PENDING_REFUSED"
            }
        }
    }
}

/**
 * Per-app inbound catalogue. Generalizes PR #255's CODE_ASSIST caller
 * allow-list so that PR can rebase onto [callerIdentities] for
 * `code-assist` instead of keeping a hardcoded File-Manager list.
 *
 * Cert fingerprints live only in [KnownNodeSigningCatalog] /
 * [KnownFleet]. This object never copies a SHA-256 literal.
 */
object InboundEntryPointCatalog {
    val VICTORSUITE = InboundEntryPoint(
        id = "victorsuite",
        displayName = "VictorSuite",
        packages = listOf("com.termux"),
        capability = "companion.assist.victorsuite",
        certStatus = InboundCertStatus.ENFORCED,
        kind = InboundEntryKind.INCLUDED,
        certTodo = "TODO: pin a VictorSuite RELEASE cert for pending " +
            "com.victorious.suite after PR #51 merges — do not invent. " +
            "Live inbound is com.termux only (KnownFleet.VICTORSUITE).",
        packageSource = "KnownFleet.VICTORSUITE / VictorSuite applicationId (live default com.termux)",
        notes = "TERMUX_IPC. Pending com.victorious.suite rename is flag-off and is not an inbound caller.",
    )

    val VICTOR_SMS = InboundEntryPoint(
        id = "victorsms",
        displayName = "VictorSMS",
        packages = listOf("com.victorious.sms"),
        capability = "companion.assist.victorsms",
        certStatus = InboundCertStatus.PENDING_REFUSED,
        kind = InboundEntryKind.INCLUDED,
        certTodo = "TODO: enforce KnownNodeSigningCatalog.VICTOR_SMS_DEBUG_SHA256_UNENFORCED " +
            "after the VictorSMS scaffold merges, then pin RELEASE when published. " +
            "The documented debug value is unenforced — inbound must not use it.",
        packageSource = "KnownFleet.VICTOR_SMS / Victorious93/victorsms applicationId",
        notes = "Debug cert is documented but unenforced (outbound fail-open). Inbound refuses.",
    )

    val VICTOR_CAM = InboundEntryPoint(
        id = "victorcam",
        displayName = "VictorCam",
        packages = listOf("com.victorious.cam"),
        capability = "companion.assist.victorcam",
        certStatus = InboundCertStatus.PENDING_REFUSED,
        kind = InboundEntryKind.INCLUDED,
        certTodo = "TODO: pin VictorCam debug + release SHA-256 in KnownNodeSigningCatalog " +
            "when Don publishes them. None is catalogued today.",
        packageSource = "Victorious93/victorcam applicationId; fleet-manifest ROADMAP",
        notes = "Not a KnownFleet member. No catalogued signing cert.",
    )

    val VICTOR_TRADE = InboundEntryPoint(
        id = "victortrade",
        displayName = "VictorTrade",
        packages = listOf("com.victorious.trade"),
        capability = "companion.assist.victortrade",
        certStatus = InboundCertStatus.PENDING_REFUSED,
        kind = InboundEntryKind.INCLUDED,
        certTodo = "TODO: confirm applicationId from a public Victorious93/victortrade " +
            "(or equivalent) repo when one exists, then pin debug + release SHA-256 " +
            "in KnownNodeSigningCatalog. No repo and no KnownFleet row on 2026-10-28.",
        packageSource = "Don-specified in the inbound-entry-points request " +
            "(com.victorious.trade). [Guessing] — not in KnownFleet and no public repo found.",
        notes = "Package id is unverified against a repo. Inbound refuses every caller.",
    )

    val VICTOR_TASKBAR = InboundEntryPoint(
        id = "victortaskbar",
        displayName = "VictorTaskbar",
        packages = listOf("com.victorious.taskbar"),
        capability = "companion.assist.victortaskbar",
        certStatus = InboundCertStatus.PENDING_REFUSED,
        kind = InboundEntryKind.INCLUDED,
        certTodo = "TODO: pin VictorTaskbar debug + release SHA-256 in KnownNodeSigningCatalog " +
            "when the app ships an APK. fleet-manifest lists it as README-only scaffold.",
        packageSource = "Victorious93/victortaskbar applicationId; fleet-manifest ROADMAP / apps.yml comment",
        notes = "Not a KnownFleet member. No catalogued signing cert.",
    )

    val VICTOR_LAUNCH = InboundEntryPoint(
        id = "victorlaunch",
        displayName = "VictorLaunch",
        packages = listOf("com.victorious.launch"),
        capability = "companion.assist.victorlaunch",
        certStatus = InboundCertStatus.ENFORCED,
        kind = InboundEntryKind.INCLUDED,
        certTodo = "TODO: pin VictorLaunch RELEASE SHA-256 when published — do not invent.",
        packageSource = "KnownFleet.VICTOR_LAUNCH / Victorious93/victorlaunch applicationId",
        notes = "Debug pin is KnownNodeSigningCatalog.VICTOR_LAUNCH_DEBUG_SHA256.",
    )

    val BROWSER = InboundEntryPoint(
        id = "browser",
        displayName = "Victorious Browser",
        packages = listOf("com.victorious.browser"),
        capability = "companion.assist.browser",
        certStatus = InboundCertStatus.ENFORCED,
        kind = InboundEntryKind.INCLUDED,
        certTodo = "TODO: pin Browser own-key RELEASE SHA-256 when published — do not invent.",
        packageSource = "KnownFleet.BROWSER / Victorious93/victorious-browser applicationId",
        notes = "Debug pin is KnownNodeSigningCatalog.VICTORIOUS_BROWSER_DEBUG_SHA256.",
    )

    val MESH = InboundEntryPoint(
        id = "mesh",
        displayName = "Victorious Mesh",
        packages = listOf("com.victorious.mesh"),
        capability = "companion.assist.mesh",
        certStatus = InboundCertStatus.ENFORCED,
        kind = InboundEntryKind.INCLUDED,
        certTodo = null,
        packageSource = "KnownFleet.MESH / Victorious93/victorious-mesh applicationId",
        notes = "Debug + own RELEASE pins are on KnownNodeSigningCatalog.VICTORIOUS_MESH_*.",
    )

    val VICTOR_MANGO = InboundEntryPoint(
        id = "victormango",
        displayName = "VictorMango",
        packages = listOf("com.victorious.mango"),
        capability = "companion.assist.victormango",
        certStatus = InboundCertStatus.PENDING_REFUSED,
        kind = InboundEntryKind.INCLUDED,
        certTodo = "TODO: pin VictorMango debug + release SHA-256 in KnownNodeSigningCatalog. " +
            "fleet-manifest apps.yml lists applicationId com.victorious.mango with empty pinnedCertSha256.",
        packageSource = "Victorious93/victormango applicationId; fleet-manifest apps.yml",
        notes = "Not a KnownFleet member. No catalogued signing cert.",
    )

    val VICTOR_SPOOF = InboundEntryPoint(
        id = "victorspoof",
        displayName = "VictorSpoof",
        packages = listOf("com.victorious.spoof"),
        capability = "companion.assist.victorspoof",
        certStatus = InboundCertStatus.PENDING_REFUSED,
        kind = InboundEntryKind.INCLUDED,
        certTodo = "TODO: pin VictorSpoof debug + release SHA-256 in KnownNodeSigningCatalog " +
            "when published. KnownFleet.VICTOR_SPOOF expectedSigningCertSha256 is empty.",
        packageSource = "KnownFleet.VICTOR_SPOOF / Victorious93/victorspoof applicationId",
        notes = "Novelty/prank caller-ID. Inbound assist never places a call. Certs TBD.",
    )

    val ROOTFORGE = InboundEntryPoint(
        id = "rootforge",
        displayName = "rootforge-os",
        packages = listOf("ai.rootforge.os.debug", "ai.rootforge.os"),
        capability = "companion.assist.rootforge",
        certStatus = InboundCertStatus.ENFORCED,
        kind = InboundEntryKind.INCLUDED,
        laterAddition = true,
        certTodo = "TODO: pin ai.rootforge.os RELEASE SHA-256 when a release APK exists. " +
            "Only ai.rootforge.os.debug is catalogued (KnownNodeSigningCatalog.ROOTFORGE_DEBUG_SHA256). " +
            "The release package is refused until that pin exists.",
        packageSource = "KnownFleet.ROOTFORGE (debug) + :app FleetCatalog.ROOTFORGE_PACKAGE / " +
            "Victorious93/rootforge-os docs (ai.rootforge.os)",
        notes = "Later addition — Don to confirm. Debug package is enforced; release package is pending-refused.",
    )

    val VICTOR_GPS = InboundEntryPoint(
        id = "victorgps",
        displayName = "VictorGPS",
        packages = listOf("com.victorious.gps"),
        capability = "companion.assist.victorgps",
        certStatus = InboundCertStatus.PENDING_REFUSED,
        kind = InboundEntryKind.INCLUDED,
        laterAddition = true,
        certTodo = "TODO: pin VictorGPS debug + release SHA-256 in KnownNodeSigningCatalog. " +
            "Victorious93/victorgps documents a committed debug keystore but no SHA-256 " +
            "is catalogued here — do not invent one from that JKS.",
        packageSource = "Victorious93/victorgps applicationId",
        notes = "Later addition — Don to confirm. Not a KnownFleet member. Inbound refuses.",
    )

    val KEYBOARD = InboundEntryPoint(
        id = "keyboard",
        displayName = "Victorious Keyboard",
        packages = listOf("com.victorious.keyboard", "com.victorious.keyboard.debug"),
        capability = "companion.assist.keyboard",
        certStatus = InboundCertStatus.ENFORCED,
        kind = InboundEntryKind.INCLUDED,
        laterAddition = true,
        certTodo = "TODO: pin Keyboard RELEASE SHA-256 when published. " +
            ".bench stays uncatalogued and is not an inbound caller.",
        packageSource = "KnownFleet.KEYBOARD / Victorious93/Victorious-Keyboard applicationId",
        notes = "Later addition — Don to confirm. Never forwards typed text or keystrokes. " +
            ".bench is not allowed.",
    )

    val FILE_MANAGER_CODE_ASSIST = InboundEntryPoint(
        id = "file-manager-code-assist",
        displayName = "VictorFiles (CODE_ASSIST)",
        packages = listOf("ai.victorious.filemanager", "ai.victorious.filemanager.debug"),
        capability = CompanionAssist.CODE_ASSIST_CAPABILITY,
        certStatus = InboundCertStatus.ENFORCED,
        kind = InboundEntryKind.PATTERN,
        certTodo = "TODO: pin File-Manager RELEASE cert when one exists — do not invent. " +
            "Inbound CODE_ASSIST (PR #255) uses KnownNodeSigningCatalog.FILE_MANAGER_DEBUG_UNSTABLE_CI_SECRETS.",
        packageSource = "KnownFleet.FILE_MANAGER / Victorious93/File-Manager applicationId",
        notes = "Pattern from PR #255. Not one of the 13 COMPANION_ASSIST callers. " +
            "Outbound Messenger stays fail-open; this inbound pin is the documented debug constant.",
    )

    val VICTORY_PED = InboundEntryPoint(
        id = "victoryped",
        displayName = "VictoryPed",
        packages = listOf("com.victorious.victoryped"),
        capability = "companion.assist.excluded",
        certStatus = InboundCertStatus.PENDING_REFUSED,
        kind = InboundEntryKind.EXCLUDED,
        certTodo = null,
        packageSource = "Victorious93/fleet-manifest ROADMAP (private repo; not polled)",
        notes = "Excluded on purpose. Social / pedigree app. Must not be controllable by droidcommand-AI.",
    )

    val VICTOR_LINK64 = InboundEntryPoint(
        id = "victorlink64",
        displayName = "VictorLink64",
        packages = listOf("org.victoriousframework.victorlink64"),
        capability = "companion.assist.excluded",
        certStatus = InboundCertStatus.PENDING_REFUSED,
        kind = InboundEntryKind.EXCLUDED,
        certTodo = null,
        packageSource = "Victorious93/VictorLink64 applicationId; fleet-manifest ROADMAP (NOT_IN_FLEET_REMOTE)",
        notes = "Excluded on purpose. Game emulator. Must not be controllable by droidcommand-AI.",
    )

    val MATCH_VICTORIOUS = InboundEntryPoint(
        id = "matchvictorious",
        displayName = "MatchVictorious",
        packages = listOf("com.victorious.match"),
        capability = "companion.assist.excluded",
        certStatus = InboundCertStatus.PENDING_REFUSED,
        kind = InboundEntryKind.EXCLUDED,
        certTodo = null,
        packageSource = "Victorious93/matchvictorious applicationId; fleet-manifest ROADMAP (SOCIAL)",
        notes = "Excluded on purpose. Dating import hub. Must not be controllable by droidcommand-AI.",
    )

    val VICTOROPS_ZOMBIES = InboundEntryPoint(
        id = "victorops-zombies",
        displayName = "VictorOps Zombies",
        packages = listOf("com.victorious.zombie"),
        capability = "companion.assist.excluded",
        certStatus = InboundCertStatus.PENDING_REFUSED,
        kind = InboundEntryKind.EXCLUDED,
        certTodo = null,
        packageSource = "Victorious93/victorops-zombies README; fleet-manifest ROADMAP (NOT_IN_FLEET_REMOTE)",
        notes = "Excluded on purpose. Game. Must not be controllable by droidcommand-AI.",
    )

    val INCLUDED: List<InboundEntryPoint> = listOf(
        VICTORSUITE,
        VICTOR_SMS,
        VICTOR_CAM,
        VICTOR_TRADE,
        VICTOR_TASKBAR,
        VICTOR_LAUNCH,
        BROWSER,
        MESH,
        VICTOR_MANGO,
        VICTOR_SPOOF,
        ROOTFORGE,
        VICTOR_GPS,
        KEYBOARD,
    )

    val EXCLUDED: List<InboundEntryPoint> = listOf(
        VICTORY_PED,
        VICTOR_LINK64,
        MATCH_VICTORIOUS,
        VICTOROPS_ZOMBIES,
    )

    val ALL: List<InboundEntryPoint> = INCLUDED + EXCLUDED + listOf(FILE_MANAGER_CODE_ASSIST)

    init {
        require(INCLUDED.size == 13) { "Expected 13 included inbound apps, got ${INCLUDED.size}" }
        require(EXCLUDED.size == 4) { "Expected 4 excluded inbound apps, got ${EXCLUDED.size}" }
        val packages = ALL.flatMap { it.packages }
        require(packages.size == packages.toSet().size) {
            "Duplicate inbound package: ${packages.groupingBy { it }.eachCount().filter { it.value > 1 }.keys}"
        }
        for (entry in INCLUDED.filter { it.certStatus == InboundCertStatus.ENFORCED }) {
            val enforcedPkgs = entry.packages.filter { resolvedPins(entry, it).isNotEmpty() }
            require(enforcedPkgs.isNotEmpty()) {
                "${entry.id} is ENFORCED but KnownNodeSigningCatalog has no pin for ${entry.packages}"
            }
        }
    }

    fun includedByPackage(packageName: String): InboundEntryPoint? =
        INCLUDED.find { packageName in it.packages }

    fun excludedByPackage(packageName: String): InboundEntryPoint? =
        EXCLUDED.find { packageName in it.packages }

    fun byCapability(capability: String): InboundEntryPoint? =
        ALL.find { it.capability == capability }

    fun isExcluded(packageName: String): Boolean = excludedByPackage(packageName) != null

    /**
     * Caller identities for one capability. Pins come from
     * [KnownNodeSigningCatalog] only. Empty expected certs + fail-closed
     * policy refuse PENDING_REFUSED rows.
     *
     * `code-assist` is the File-Manager CODE_ASSIST allow-list (#255).
     * `companion.assist` (or a blank capability) is every ENFORCED
     * included COMPANION_ASSIST package.
     */
    fun callerIdentities(capability: String? = CompanionAssist.SHARED_CAPABILITY): List<NodeSigningIdentity> {
        val entries = when {
            capability == CompanionAssist.CODE_ASSIST_CAPABILITY -> listOf(FILE_MANAGER_CODE_ASSIST)
            capability.isNullOrBlank() || capability == CompanionAssist.SHARED_CAPABILITY ->
                INCLUDED.filter { it.certStatus == InboundCertStatus.ENFORCED }
            else -> INCLUDED.filter { it.capability == capability && it.certStatus == InboundCertStatus.ENFORCED }
        }
        return entries.flatMap { entry ->
            entry.packages.mapNotNull { pkg ->
                val pins = resolvedPins(entry, pkg)
                if (pins.isEmpty()) {
                    null
                } else {
                    NodeSigningIdentity(
                        packageName = pkg,
                        expectedCerts = pins,
                        memberId = entry.id,
                        integrationClass = KnownNodeSigningCatalog.identityFor(pkg)?.integrationClass,
                        notes = "Inbound ${entry.capability}. Pins from KnownNodeSigningCatalog. " +
                            entry.notes,
                    )
                }
            }
        }
    }

    fun callerVerifier(capability: String? = CompanionAssist.SHARED_CAPABILITY): NodeSignatureVerifier =
        NodeSignatureVerifier(
            identities = callerIdentities(capability),
            missingExpectedPolicy = MissingExpectedCertPolicy.REFUSE,
        )

    /**
     * Fail-closed caller check. Missing identity, excluded package,
     * unknown package, pending-refused cert, or cert mismatch all refuse
     * unless [pairing] already has a TOFU link for this package+cert.
     * Excluded apps stay refused. [pairing] null (the default) keeps the
     * original pending-refused / pin-mismatch refusals.
     */
    fun verifyCaller(
        packageName: String?,
        observedFingerprints: Collection<String>,
        capability: String? = CompanionAssist.SHARED_CAPABILITY,
        pairing: PasswordPairingService? = null,
    ): NodeSignatureDecision {
        if (packageName.isNullOrBlank()) {
            return NodeSignatureDecision.Refused(
                packageName = packageName.orEmpty(),
                kind = NodeSignatureRefusal.EXPECTED_CERT_MISSING,
                message = "Refusing inbound entry point: caller identity is missing " +
                    "(activity was not started for result, or getCallingPackage() " +
                    "and getCallingActivity() were both empty).",
            )
        }
        excludedByPackage(packageName)?.let { excluded ->
            return NodeSignatureDecision.Refused(
                packageName = packageName,
                kind = NodeSignatureRefusal.EXPECTED_CERT_MISSING,
                message = "Refusing inbound entry point: ${excluded.displayName} " +
                    "($packageName) is excluded on purpose and must not be " +
                    "controllable by droidcommand-AI. ${excluded.notes}",
            )
        }
        val included = includedByPackage(packageName)
        if (capability == CompanionAssist.CODE_ASSIST_CAPABILITY) {
            if (packageName !in FILE_MANAGER_CODE_ASSIST.packages) {
                return NodeSignatureDecision.Refused(
                    packageName = packageName,
                    kind = NodeSignatureRefusal.EXPECTED_CERT_MISSING,
                    message = "Refusing CODE_ASSIST: '$packageName' is not on the " +
                        "File-Manager inbound allow-list.",
                )
            }
            return callerVerifier(CompanionAssist.CODE_ASSIST_CAPABILITY)
                .verify(packageName, observedFingerprints)
        }
        if (included == null) {
            return NodeSignatureDecision.Refused(
                packageName = packageName,
                kind = NodeSignatureRefusal.EXPECTED_CERT_MISSING,
                message = "Refusing inbound COMPANION_ASSIST: '$packageName' is not " +
                    "one of the 13 included fleet callers.",
            )
        }
        if (included.certStatus == InboundCertStatus.PENDING_REFUSED) {
            passwordLinkOverride(packageName, observedFingerprints, pairing)?.let { return it }
            return NodeSignatureDecision.Refused(
                packageName = packageName,
                kind = NodeSignatureRefusal.EXPECTED_CERT_MISSING,
                message = "Refusing inbound COMPANION_ASSIST for ${included.displayName} " +
                    "($packageName): cert is undocumented or unenforced (pending-refused). " +
                    (included.certTodo ?: "TODO: pin the signing cert in KnownNodeSigningCatalog."),
            )
        }
        val pins = resolvedPins(included, packageName)
        if (pins.isEmpty()) {
            passwordLinkOverride(packageName, observedFingerprints, pairing)?.let { return it }
            return NodeSignatureDecision.Refused(
                packageName = packageName,
                kind = NodeSignatureRefusal.EXPECTED_CERT_MISSING,
                message = "Refusing inbound COMPANION_ASSIST for ${included.displayName} " +
                    "($packageName): ENFORCED row has no KnownNodeSigningCatalog pin " +
                    "for this package. ${included.certTodo ?: ""}".trim(),
            )
        }
        val catalogued = callerVerifier(CompanionAssist.SHARED_CAPABILITY)
            .verify(packageName, observedFingerprints)
        if (catalogued is NodeSignatureDecision.Allowed) return catalogued
        passwordLinkOverride(packageName, observedFingerprints, pairing)?.let { return it }
        return catalogued
    }

    /**
     * Already-linked TOFU override. Does not create a link. Excluded
     * packages never match. A changed cert stays refused.
     */
    fun passwordLinkOverride(
        packageName: String,
        observedFingerprints: Collection<String>,
        pairing: PasswordPairingService?,
    ): NodeSignatureDecision? {
        if (pairing == null) return null
        if (isExcluded(packageName)) return null
        val cert = observedFingerprints.mapNotNull { PasswordPairing.normalizeCert(it) }.firstOrNull()
            ?: return null
        return when (val decision = pairing.acceptLinked(packageName, cert)) {
            is PasswordPairingDecision.Allowed -> NodeSignatureDecision.Allowed(
                packageName = packageName,
                matchedFingerprint = SigningCertSha256.parse(cert)?.canonical ?: cert,
                message = decision.message,
            )
            is PasswordPairingDecision.Refused -> null
        }
    }

    /**
     * Resolve pins from the single cert catalogue. Never invent.
     *
     * File-Manager CODE_ASSIST is the documented exception from PR #255:
     * outbound [KnownFleet.FILE_MANAGER] stays fail-open, inbound uses
     * [KnownNodeSigningCatalog.FILE_MANAGER_DEBUG_UNSTABLE_CI_SECRETS].
     */
    fun resolvedPins(entry: InboundEntryPoint, packageName: String): List<SigningCertSha256> {
        if (packageName !in entry.packages) return emptyList()
        if (entry.kind == InboundEntryKind.EXCLUDED) return emptyList()
        if (entry.certStatus == InboundCertStatus.PENDING_REFUSED) return emptyList()
        val catalogued = KnownNodeSigningCatalog.identityFor(packageName)?.expectedCerts.orEmpty()
        if (catalogued.isNotEmpty()) return catalogued
        if (entry.id == FILE_MANAGER_CODE_ASSIST.id) {
            return listOf(
                SigningCertSha256.parseOrThrow(
                    KnownNodeSigningCatalog.FILE_MANAGER_DEBUG_UNSTABLE_CI_SECRETS,
                ),
            )
        }
        return emptyList()
    }
}

/**
 * Contract v1 for inbound companion assist from the 13 included fleet
 * apps. Field names are this repo's (no per-app PHASE17 document exists
 * for these callers). CODE_ASSIST stays on `ai.droidcommand.action.CODE_ASSIST`
 * in PR #255.
 *
 * JVM-testable: request parse, 60 000-char cap, caller pin, result JSON.
 * The Android Activity is a thin shell around this.
 */
object CompanionAssist {
    const val ACTION = "ai.droidcommand.action.COMPANION_ASSIST"
    const val EXTRA_REQUEST_JSON = "ai.droidcommand.extra.REQUEST_JSON"
    const val EXTRA_RESULT_JSON = "ai.droidcommand.extra.RESULT_JSON"

    const val MAX_PROMPT_CHARS = 60_000
    const val CONTRACT_VERSION = 1
    const val TASK_ASSIST = "assist"
    const val TASK_PAIR = "pair"
    const val SHARED_CAPABILITY = "companion.assist"
    const val CODE_ASSIST_CAPABILITY = "code-assist"
    const val PREVIEW_CHARS = 1_200

    fun callerVerifier(): NodeSignatureVerifier =
        InboundEntryPointCatalog.callerVerifier(SHARED_CAPABILITY)

    fun verifyCaller(
        packageName: String?,
        observedFingerprints: Collection<String>,
        pairing: PasswordPairingService? = null,
    ): NodeSignatureDecision =
        InboundEntryPointCatalog.verifyCaller(
            packageName,
            observedFingerprints,
            SHARED_CAPABILITY,
            pairing,
        )

    fun parseRequest(raw: String?): CompanionAssistParse {
        if (raw.isNullOrBlank()) {
            return CompanionAssistParse.Invalid("Missing $EXTRA_REQUEST_JSON.")
        }
        val root = try {
            JSON.parseToJsonElement(raw)
        } catch (_: Exception) {
            return CompanionAssistParse.Invalid("REQUEST_JSON is not valid JSON.")
        }
        val obj = root as? JsonObject
            ?: return CompanionAssistParse.Invalid("REQUEST_JSON must be a JSON object.")

        val version = versionOf(obj)
            ?: return CompanionAssistParse.Invalid("REQUEST_JSON field 'v' must be $CONTRACT_VERSION.")
        if (version != CONTRACT_VERSION) {
            return CompanionAssistParse.Invalid("Unsupported COMPANION_ASSIST contract v=$version.")
        }

        val task = obj.string("task") ?: TASK_ASSIST
        if (task != TASK_ASSIST && task != TASK_PAIR) {
            return CompanionAssistParse.Invalid(
                "REQUEST_JSON field 'task' must be '$TASK_ASSIST' or '$TASK_PAIR' " +
                    "(refusing '$task' — this path never runs tools or remote.command).",
            )
        }
        val pairingObj = obj["pairing"] as? JsonObject
        val pairing = pairingObj?.let {
            PairingProof(
                challengeId = it.string("challengeId"),
                nonceHex = it.string("nonce") ?: it.string("nonceHex"),
                macHex = it.string("mac") ?: it.string("macHex"),
            )
        }
        val needChallenge = pairingObj?.boolean("needChallenge") ?: false
        if (task == TASK_PAIR) {
            return CompanionAssistParse.Valid(
                CompanionAssistRequest(
                    capability = obj.string("capability").orEmpty(),
                    prompt = obj.string("prompt").orEmpty(),
                    context = obj.string("context").orEmpty(),
                    instruction = obj.string("instruction").orEmpty(),
                    destructive = false,
                    pairing = pairing,
                    needChallenge = needChallenge,
                    task = TASK_PAIR,
                ),
            )
        }
        val prompt = obj.string("prompt")
            ?: return CompanionAssistParse.Invalid("REQUEST_JSON field 'prompt' is required.")
        if (prompt.isEmpty()) {
            return CompanionAssistParse.Invalid("REQUEST_JSON field 'prompt' is empty.")
        }
        if (prompt.length > MAX_PROMPT_CHARS) {
            return CompanionAssistParse.Invalid(
                "REQUEST_JSON field 'prompt' exceeds $MAX_PROMPT_CHARS characters " +
                    "(got ${prompt.length}).",
            )
        }
        val destructive = obj.boolean("destructive") ?: false
        return CompanionAssistParse.Valid(
            CompanionAssistRequest(
                capability = obj.string("capability").orEmpty(),
                prompt = prompt,
                context = obj.string("context").orEmpty(),
                instruction = obj.string("instruction").orEmpty(),
                destructive = destructive,
                pairing = pairing,
                needChallenge = needChallenge,
            ),
        )
    }

    /**
     * After parse: the optional request capability must match the caller's
     * catalogue row. A `destructive: true` flag does not run tools — it
     * only marks the consent as confirmation_required (the Allow tap).
     */
    fun bindToCaller(
        request: CompanionAssistRequest,
        packageName: String,
        pairing: PasswordPairingService? = null,
        observedCerts: Collection<String> = emptyList(),
    ): CompanionAssistParse {
        val entry = InboundEntryPointCatalog.includedByPackage(packageName)
            ?: return CompanionAssistParse.Invalid(
                "Caller '$packageName' is not an included COMPANION_ASSIST app.",
            )
        val linked = pairing != null &&
            observedCerts.any { pairing.isLinked(packageName, it) }
        val canOfferLink = pairing != null &&
            pairing.isEnabled() &&
            pairing.hasPassword() &&
            !InboundEntryPointCatalog.isExcluded(packageName)
        if (InboundEntryPointCatalog.resolvedPins(entry, packageName).isEmpty() &&
            entry.certStatus == InboundCertStatus.ENFORCED &&
            !linked &&
            !canOfferLink
        ) {
            return CompanionAssistParse.Invalid(
                "Caller '$packageName' has no catalogued inbound pin. " +
                    (entry.certTodo ?: ""),
            )
        }
        if (entry.certStatus == InboundCertStatus.PENDING_REFUSED && !linked && !canOfferLink) {
            return CompanionAssistParse.Invalid(
                "Caller '${entry.displayName}' is pending-refused. " +
                    (entry.certTodo ?: ""),
            )
        }
        if (request.capability.isNotBlank() &&
            request.capability != entry.capability &&
            request.capability != SHARED_CAPABILITY
        ) {
            return CompanionAssistParse.Invalid(
                "REQUEST_JSON capability '${request.capability}' does not match " +
                    "caller '${entry.id}' (${entry.capability}).",
            )
        }
        return CompanionAssistParse.Valid(
            request.copy(capability = entry.capability, callerId = entry.id),
        )
    }

    fun buildPrompt(request: CompanionAssistRequest): String {
        val body = request.prompt.trim()
        val extra = buildString {
            if (request.instruction.isNotBlank()) {
                append(request.instruction.trim()).append("\n\n")
            }
            if (request.context.isNotBlank()) {
                append(request.context.trim()).append("\n\n")
            }
        }
        return extra + body
    }

    fun successJson(text: String): String = JSON.encodeToString(
        JsonObject(
            mapOf(
                "v" to JsonPrimitive(CONTRACT_VERSION),
                "ok" to JsonPrimitive(true),
                "text" to JsonPrimitive(text),
            ),
        ),
    )

    fun pairingChallengeJson(challengeId: String, nonceHex: String): String = JSON.encodeToString(
        JsonObject(
            mapOf(
                "v" to JsonPrimitive(CONTRACT_VERSION),
                "ok" to JsonPrimitive(true),
                "protocol" to JsonPrimitive(PasswordPairing.PROTOCOL),
                "challengeId" to JsonPrimitive(challengeId),
                "nonce" to JsonPrimitive(nonceHex),
            ),
        ),
    )

    fun errorJson(message: String): String = JSON.encodeToString(
        JsonObject(
            mapOf(
                "v" to JsonPrimitive(CONTRACT_VERSION),
                "ok" to JsonPrimitive(false),
                "error" to JsonPrimitive(message),
            ),
        ),
    )

    fun preview(text: String, maxChars: Int = PREVIEW_CHARS): String {
        if (text.length <= maxChars) return text
        return text.take(maxChars) + "\n… (${text.length - maxChars} more characters)"
    }

    private fun versionOf(obj: JsonObject): Int? {
        val value = obj["v"] ?: return CONTRACT_VERSION
        val primitive = value as? JsonPrimitive ?: return null
        primitive.intOrNull?.let { return it }
        return primitive.contentOrNull?.toIntOrNull()
    }

    private fun JsonObject.string(key: String): String? {
        val value = this[key] ?: return null
        val primitive = value as? JsonPrimitive ?: return null
        if (primitive.isString) return primitive.content
        return primitive.contentOrNull
    }

    private fun JsonObject.boolean(key: String): Boolean? {
        val value = this[key] ?: return null
        val primitive = value as? JsonPrimitive ?: return null
        primitive.booleanOrNull?.let { return it }
        return when (primitive.contentOrNull?.lowercase()) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

    private val JSON = Json { ignoreUnknownKeys = true }
}

data class CompanionAssistRequest(
    val capability: String,
    val prompt: String,
    val context: String,
    val instruction: String,
    val destructive: Boolean,
    val callerId: String = "",
    val pairing: PairingProof? = null,
    val needChallenge: Boolean = false,
    val task: String = CompanionAssist.TASK_ASSIST,
)

sealed class CompanionAssistParse {
    data class Valid(val request: CompanionAssistRequest) : CompanionAssistParse()

    data class Invalid(val reason: String) : CompanionAssistParse()
}
