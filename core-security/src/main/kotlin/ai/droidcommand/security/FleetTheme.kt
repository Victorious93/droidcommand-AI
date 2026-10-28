package ai.droidcommand.security

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Reader-side contract for the fleet theme VictorLaunch publishes
 * (`docs/specs/FLEET_THEME.md` in `Victorious93/victorlaunch`, schemaVersion 1).
 *
 * VictorLaunch is the **sole writer**. This module only parses and verifies
 * a queried row. It never insert/update/deletes the provider, and it does
 * not invent colors: a missing or invalid `palette.*` field stays null so
 * the app keeps its existing default.
 *
 * Auth is package + certificate pin ([KnownFleet.VICTOR_LAUNCH]), not a
 * shared signature permission and not a shared fleet key.
 */
object FleetThemeContract {
    const val AUTHORITY = "com.victorious.launch.theme"
    const val PATH_CURRENT = "current"
    const val URI = "content://$AUTHORITY/$PATH_CURRENT"
    const val MIME = "vnd.android.cursor.item/vnd.com.victorious.launch.theme.current"
    const val ACTION_CHANGED = "com.victorious.launch.action.FLEET_THEME_CHANGED"
    const val SCHEMA_VERSION = 1
    const val TRUST_HINT = "Trust installed fleet share apps"

    /**
     * Writer package. Reuses [KnownFleet.VICTOR_LAUNCH] — do not hardcode a
     * second copy.
     */
    val WRITER_PACKAGE: String
        get() = KnownFleet.VICTOR_LAUNCH.androidPackages.single()

    /**
     * Expected signing-cert SHA-256 values. Reuses
     * [KnownFleet.VICTOR_LAUNCH.expectedSigningCertSha256] (today
     * [KnownNodeSigningCatalog.VICTOR_LAUNCH_DEBUG_SHA256]). The revoked
     * `1B:06:A8:…:BA:CA` pin is not in that list and must not be accepted.
     */
    val EXPECTED_CERTS: List<String>
        get() = KnownFleet.VICTOR_LAUNCH.expectedSigningCertSha256

    object Columns {
        const val SCHEMA_VERSION = "schemaVersion"
        const val MODE = "mode"
        const val ICON_STYLE = "iconStyle"
        const val ICON_PACK_PACKAGE = "iconPackPackage"
        const val JSON = "json"
        const val UPDATED_AT_MS = "updatedAtMs"
        const val BACKGROUND = "background"
        const val SURFACE = "surface"
        const val PRIMARY = "primary"
        const val SECONDARY = "secondary"
        const val ON_BACKGROUND = "onBackground"
        const val MUTED = "muted"
        const val DANGER = "danger"
        const val STALE = "stale"
        const val TILE = "tile"
    }

    val PALETTE_COLUMNS: List<String> = listOf(
        Columns.BACKGROUND,
        Columns.SURFACE,
        Columns.PRIMARY,
        Columns.SECONDARY,
        Columns.ON_BACKGROUND,
        Columns.MUTED,
        Columns.DANGER,
        Columns.STALE,
        Columns.TILE,
    )
}

enum class FleetThemeMode {
    LIGHT,
    DARK,
    NEON_FLEET,
    MATERIAL_YOU,
    ;

    companion object {
        fun parse(raw: String?): FleetThemeMode? {
            val name = raw?.trim()?.uppercase() ?: return null
            return entries.find { it.name == name }
        }
    }
}

/**
 * Hex colors from a provider row or the `palette` JSON object.
 * Each field is `#RRGGBB` / `#AARRGGBB` or null (missing / invalid —
 * the app keeps its existing default; this type never invents a color).
 */
data class FleetThemePalette(
    val background: String? = null,
    val surface: String? = null,
    val primary: String? = null,
    val secondary: String? = null,
    val onBackground: String? = null,
    val muted: String? = null,
    val danger: String? = null,
    val stale: String? = null,
    val tile: String? = null,
) {
    fun isEmpty(): Boolean =
        background == null &&
            surface == null &&
            primary == null &&
            secondary == null &&
            onBackground == null &&
            muted == null &&
            danger == null &&
            stale == null &&
            tile == null
}

/**
 * App defaults copied from `:app` `ui/theme/Color.kt`. Keep the hexes in
 * lockstep with that file. [stale] has no Color.kt token, so it stays
 * null — do not invent one.
 */
object FleetThemeDefaults {
    val APP: FleetThemePalette = FleetThemePalette(
        background = "#FF050912",
        surface = "#FF0A1220",
        primary = "#FF2F6FDB",
        secondary = "#FF5B6CFF",
        onBackground = "#FFE8F0FC",
        muted = "#FF8FA3C2",
        danger = "#FFFF5A52",
        stale = null,
        tile = "#FF111B2E",
    )
}

data class FleetThemeSnapshot(
    val schemaVersion: Int,
    val mode: FleetThemeMode?,
    val palette: FleetThemePalette,
    val updatedAtMs: Long? = null,
    val iconStyle: String? = null,
    val iconPackPackage: String? = null,
)

sealed class FleetThemeSource {
    data class Row(
        val publisherPackage: String,
        val fingerprints: Collection<String>,
        val columns: Map<String, String?>,
    ) : FleetThemeSource()

    /** Provider threw [SecurityException] — we are not pinned/trusted. */
    data object SecurityDenied : FleetThemeSource()

    /** Authority not installed / resolveContentProvider returned null. */
    data object NotInstalled : FleetThemeSource()

    /** Query returned no row. */
    data object Empty : FleetThemeSource()
}

sealed class FleetThemeDecision {
    data class Apply(val snapshot: FleetThemeSnapshot) : FleetThemeDecision()

    /** schemaVersion != 1, empty row, or VictorLaunch not installed. Keep the current theme. */
    data class KeepCurrent(val reason: String) : FleetThemeDecision()

    /** Pin failed or SecurityException. Use the app default theme. */
    data class Fallback(
        val reason: String,
        val showTrustHint: Boolean,
    ) : FleetThemeDecision()
}

/**
 * JVM-testable fleet-theme reader. Android supplies an already-fetched
 * [FleetThemeSource]; this class never talks to ContentResolver.
 */
class FleetThemeClient(
    private val defaults: FleetThemePalette = FleetThemeDefaults.APP,
    private val log: (String) -> Unit = {},
    writerPackage: String = FleetThemeContract.WRITER_PACKAGE,
    expectedCerts: List<String> = FleetThemeContract.EXPECTED_CERTS,
) {
    private val verifier = NodeSignatureVerifier(
        identities = listOf(
            NodeSigningIdentity(
                packageName = writerPackage,
                expectedCerts = expectedCerts.map { SigningCertSha256.parseOrThrow(it) },
                memberId = KnownFleet.VICTOR_LAUNCH.id,
                integrationClass = KnownFleet.VICTOR_LAUNCH.integrationClass,
                notes = "Fleet theme writer pin — KnownFleet.VICTOR_LAUNCH.expectedSigningCertSha256",
            ),
        ),
        missingExpectedPolicy = MissingExpectedCertPolicy.REFUSE,
    )

    fun verifyPublisher(
        packageName: String,
        observedFingerprints: Collection<String>,
    ): NodeSignatureDecision = verifier.verify(packageName, observedFingerprints)

    fun evaluate(source: FleetThemeSource): FleetThemeDecision {
        return when (source) {
            is FleetThemeSource.SecurityDenied -> {
                val reason = "Fleet theme query denied (SecurityException): package+cert is not pinned by VictorLaunch."
                log(reason)
                FleetThemeDecision.Fallback(reason = reason, showTrustHint = true)
            }
            is FleetThemeSource.NotInstalled -> {
                val reason = "Fleet theme provider not installed; keeping current theme."
                log(reason)
                FleetThemeDecision.KeepCurrent(reason)
            }
            is FleetThemeSource.Empty -> {
                val reason = "Fleet theme query returned no row; keeping current theme."
                log(reason)
                FleetThemeDecision.KeepCurrent(reason)
            }
            is FleetThemeSource.Row -> evaluateRow(source)
        }
    }

    /**
     * Fill missing palette fields from [defaults]. Never invents a color
     * that is not in [defaults] (so [FleetThemePalette.stale] stays null
     * unless the row or the caller-supplied defaults provided one).
     */
    fun resolve(palette: FleetThemePalette): FleetThemePalette = FleetThemePalette(
        background = palette.background ?: defaults.background,
        surface = palette.surface ?: defaults.surface,
        primary = palette.primary ?: defaults.primary,
        secondary = palette.secondary ?: defaults.secondary,
        onBackground = palette.onBackground ?: defaults.onBackground,
        muted = palette.muted ?: defaults.muted,
        danger = palette.danger ?: defaults.danger,
        stale = palette.stale ?: defaults.stale,
        tile = palette.tile ?: defaults.tile,
    )

    private fun evaluateRow(row: FleetThemeSource.Row): FleetThemeDecision {
        val pin = verifyPublisher(row.publisherPackage, row.fingerprints)
        if (pin is NodeSignatureDecision.Refused) {
            val reason = "Fleet theme publisher pin failed: ${pin.message}"
            log(reason)
            return FleetThemeDecision.Fallback(reason = reason, showTrustHint = true)
        }

        val parsed = parseRow(row.columns)
        if (parsed == null) {
            val version = readSchemaVersion(row.columns)
            val reason = "Fleet theme schemaVersion=$version rejected (need ${FleetThemeContract.SCHEMA_VERSION}); keeping current theme."
            log(reason)
            return FleetThemeDecision.KeepCurrent(reason)
        }
        return FleetThemeDecision.Apply(parsed)
    }

    internal fun parseRow(columns: Map<String, String?>): FleetThemeSnapshot? {
        val jsonSnapshot = columns[FleetThemeContract.Columns.JSON]?.let { parseJson(it) }
        val schemaVersion = readSchemaVersion(columns, jsonSnapshot)
        if (schemaVersion != FleetThemeContract.SCHEMA_VERSION) {
            return null
        }
        val jsonPalette = jsonSnapshot?.palette
        val palette = FleetThemePalette(
            background = readHex(columns, FleetThemeContract.Columns.BACKGROUND, jsonPalette?.background),
            surface = readHex(columns, FleetThemeContract.Columns.SURFACE, jsonPalette?.surface),
            primary = readHex(columns, FleetThemeContract.Columns.PRIMARY, jsonPalette?.primary),
            secondary = readHex(columns, FleetThemeContract.Columns.SECONDARY, jsonPalette?.secondary),
            onBackground = readHex(columns, FleetThemeContract.Columns.ON_BACKGROUND, jsonPalette?.onBackground),
            muted = readHex(columns, FleetThemeContract.Columns.MUTED, jsonPalette?.muted),
            danger = readHex(columns, FleetThemeContract.Columns.DANGER, jsonPalette?.danger),
            stale = readHex(columns, FleetThemeContract.Columns.STALE, jsonPalette?.stale),
            tile = readHex(columns, FleetThemeContract.Columns.TILE, jsonPalette?.tile),
        )
        val mode = FleetThemeMode.parse(columns[FleetThemeContract.Columns.MODE] ?: jsonSnapshot?.mode)
        val updatedAtMs = columns[FleetThemeContract.Columns.UPDATED_AT_MS]?.toLongOrNull()
            ?: jsonSnapshot?.updatedAtMs
        return FleetThemeSnapshot(
            schemaVersion = schemaVersion,
            mode = mode,
            palette = palette,
            updatedAtMs = updatedAtMs,
            iconStyle = columns[FleetThemeContract.Columns.ICON_STYLE] ?: jsonSnapshot?.iconStyle,
            iconPackPackage = columns[FleetThemeContract.Columns.ICON_PACK_PACKAGE] ?: jsonSnapshot?.iconPackPackage,
        )
    }

    private fun readSchemaVersion(
        columns: Map<String, String?>,
        jsonSnapshot: FleetThemeJsonSnapshot? = null,
    ): Int? {
        val fromColumn = columns[FleetThemeContract.Columns.SCHEMA_VERSION]?.trim()?.toIntOrNull()
        if (fromColumn != null) return fromColumn
        if (jsonSnapshot != null) return jsonSnapshot.schemaVersion
        return columns[FleetThemeContract.Columns.JSON]?.let { parseJson(it)?.schemaVersion }
    }

    private fun readHex(columns: Map<String, String?>, column: String, jsonValue: String?): String? {
        val fromColumn = normalizeHex(columns[column])
        if (fromColumn != null) return fromColumn
        return normalizeHex(jsonValue)
    }

    private fun parseJson(raw: String): FleetThemeJsonSnapshot? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        return try {
            JSON.decodeFromString(FleetThemeJsonSnapshot.serializer(), text)
        } catch (_: SerializationException) {
            log("Fleet theme json column was not schemaVersion-1 JSON; ignoring it.")
            null
        } catch (_: IllegalArgumentException) {
            log("Fleet theme json column was not schemaVersion-1 JSON; ignoring it.")
            null
        }
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        fun normalizeHex(raw: String?): String? {
            val value = raw?.trim().orEmpty()
            if (value.isEmpty()) return null
            if (!HEX.matches(value)) return null
            return value.uppercase()
        }

        private val HEX = Regex("^#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$")
    }
}

@Serializable
internal data class FleetThemeJsonSnapshot(
    val schemaVersion: Int,
    val mode: String? = null,
    val iconStyle: String? = null,
    val iconPackPackage: String? = null,
    val updatedAtMs: Long? = null,
    val palette: FleetThemeJsonPalette? = null,
)

@Serializable
internal data class FleetThemeJsonPalette(
    val background: String? = null,
    val surface: String? = null,
    val primary: String? = null,
    val secondary: String? = null,
    val onBackground: String? = null,
    val muted: String? = null,
    val danger: String? = null,
    val stale: String? = null,
    val tile: String? = null,
)
