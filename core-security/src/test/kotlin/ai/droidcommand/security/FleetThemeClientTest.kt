package ai.droidcommand.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FleetThemeClientTest {
    private val client = FleetThemeClient()
    private val writer = FleetThemeContract.WRITER_PACKAGE
    private val accepted = KnownNodeSigningCatalog.VICTOR_LAUNCH_DEBUG_SHA256
    private val revoked =
        "1B:06:A8:9E:7F:F5:BA:21:C9:A4:F8:F1:F0:4F:95:E5:62:78:A1:22:18:AD:37:33:28:CE:E7:83:1D:4F:BA:CA"
    private val attacker =
        "00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF"

    private fun validColumns(
        schemaVersion: String = "1",
        primary: String? = "#FF00E5FF",
        extra: Map<String, String?> = emptyMap(),
    ): Map<String, String?> = mapOf(
        FleetThemeContract.Columns.SCHEMA_VERSION to schemaVersion,
        FleetThemeContract.Columns.MODE to "NEON_FLEET",
        FleetThemeContract.Columns.PRIMARY to primary,
    ) + extra

    private fun row(
        columns: Map<String, String?> = validColumns(),
        packageName: String = writer,
        fingerprints: Collection<String> = listOf(accepted),
    ) = FleetThemeSource.Row(packageName, fingerprints, columns)

    @Test
    fun `contract reuses KnownFleet VictorLaunch package and cert pin`() {
        assertEquals("com.victorious.launch", FleetThemeContract.WRITER_PACKAGE)
        assertEquals(
            KnownFleet.VICTOR_LAUNCH.androidPackages.single(),
            FleetThemeContract.WRITER_PACKAGE,
        )
        assertEquals(
            KnownFleet.VICTOR_LAUNCH.expectedSigningCertSha256,
            FleetThemeContract.EXPECTED_CERTS,
        )
        assertEquals(listOf(accepted), FleetThemeContract.EXPECTED_CERTS)
        assertFalse(FleetThemeContract.EXPECTED_CERTS.contains(revoked))
        assertEquals(FleetThemeContract.URI, "content://com.victorious.launch.theme/current")
        assertEquals(FleetThemeContract.ACTION_CHANGED, "com.victorious.launch.action.FLEET_THEME_CHANGED")
    }

    @Test
    fun `cert pin accepts the catalogued VictorLaunch debug fingerprint`() {
        val decision = client.verifyPublisher(writer, listOf(accepted))
        val allowed = assertIs<NodeSignatureDecision.Allowed>(decision)
        assertEquals(accepted, allowed.matchedFingerprint)
    }

    @Test
    fun `cert pin accepts compacted lowercase fingerprint`() {
        val compacted = accepted.lowercase().replace(":", "")
        val decision = client.verifyPublisher(writer, listOf(compacted))
        assertIs<NodeSignatureDecision.Allowed>(decision)
    }

    @Test
    fun `cert pin rejects the revoked VictorLaunch fingerprint`() {
        val decision = client.verifyPublisher(writer, listOf(revoked))
        val refused = assertIs<NodeSignatureDecision.Refused>(decision)
        assertEquals(NodeSignatureRefusal.CERT_MISMATCH, refused.kind)
        assertTrue(refused.expected.contains(accepted))
        assertFalse(refused.expected.contains(revoked))
    }

    @Test
    fun `cert pin rejects a different package even with the accepted cert`() {
        val decision = client.verifyPublisher("com.example.spoof", listOf(accepted))
        assertIs<NodeSignatureDecision.Refused>(decision)
    }

    @Test
    fun `evaluate falls back when the publisher cert is not pinned`() {
        val logs = mutableListOf<String>()
        val logged = FleetThemeClient(log = { logs += it })
        val decision = logged.evaluate(row(fingerprints = listOf(attacker)))
        val fallback = assertIs<FleetThemeDecision.Fallback>(decision)
        assertTrue(fallback.showTrustHint)
        assertTrue(fallback.reason.contains("pin failed"))
        assertTrue(logs.any { it.contains("pin failed") })
    }

    @Test
    fun `schemaVersion other than 1 is ignored and current theme is kept`() {
        val logs = mutableListOf<String>()
        val logged = FleetThemeClient(log = { logs += it })
        val decision = logged.evaluate(row(validColumns(schemaVersion = "2")))
        val keep = assertIs<FleetThemeDecision.KeepCurrent>(decision)
        assertTrue(keep.reason.contains("schemaVersion=2"))
        assertTrue(logs.any { it.contains("schemaVersion=2") })
    }

    @Test
    fun `missing schemaVersion is rejected`() {
        val decision = client.evaluate(
            row(
                mapOf(
                    FleetThemeContract.Columns.MODE to "DARK",
                    FleetThemeContract.Columns.PRIMARY to "#FF00E5FF",
                ),
            ),
        )
        assertIs<FleetThemeDecision.KeepCurrent>(decision)
    }

    @Test
    fun `missing palette fields fall back to the app Color kt defaults`() {
        val decision = client.evaluate(
            row(
                validColumns(
                    primary = null,
                    extra = mapOf(
                        FleetThemeContract.Columns.BACKGROUND to "#FF0B0C12",
                        FleetThemeContract.Columns.PRIMARY to "",
                        FleetThemeContract.Columns.DANGER to "not-a-color",
                    ),
                ),
            ),
        )
        val applied = assertIs<FleetThemeDecision.Apply>(decision)
        assertEquals("#FF0B0C12", applied.snapshot.palette.background)
        assertNull(applied.snapshot.palette.primary)
        assertNull(applied.snapshot.palette.danger)
        assertNull(applied.snapshot.palette.surface)

        val resolved = client.resolve(applied.snapshot.palette)
        assertEquals("#FF0B0C12", resolved.background)
        assertEquals(FleetThemeDefaults.APP.primary, resolved.primary)
        assertEquals(FleetThemeDefaults.APP.danger, resolved.danger)
        assertEquals(FleetThemeDefaults.APP.surface, resolved.surface)
        assertEquals(FleetThemeDefaults.APP.secondary, resolved.secondary)
        assertEquals(FleetThemeDefaults.APP.onBackground, resolved.onBackground)
        assertEquals(FleetThemeDefaults.APP.muted, resolved.muted)
        assertEquals(FleetThemeDefaults.APP.tile, resolved.tile)
        assertNull(resolved.stale)
        assertEquals("#FF2F6FDB", resolved.primary)
        assertEquals("#FF0A1220", resolved.surface)
    }

    @Test
    fun `resolve never invents a stale color the app does not already have`() {
        val resolved = client.resolve(FleetThemePalette())
        assertEquals(FleetThemeDefaults.APP, resolved)
        assertNull(resolved.stale)
    }

    @Test
    fun `SecurityException becomes a calm default-theme fallback with a trust hint`() {
        val logs = mutableListOf<String>()
        val logged = FleetThemeClient(log = { logs += it })
        val decision = logged.evaluate(FleetThemeSource.SecurityDenied)
        val fallback = assertIs<FleetThemeDecision.Fallback>(decision)
        assertTrue(fallback.showTrustHint)
        assertTrue(fallback.reason.contains("SecurityException"))
        assertTrue(logs.any { it.contains("SecurityException") })
        assertEquals(FleetThemeContract.TRUST_HINT, "Trust installed fleet share apps")
    }

    @Test
    fun `provider missing or empty keeps the current theme without a trust hint`() {
        val missing = client.evaluate(FleetThemeSource.NotInstalled)
        val empty = client.evaluate(FleetThemeSource.Empty)
        assertIs<FleetThemeDecision.KeepCurrent>(missing)
        assertIs<FleetThemeDecision.KeepCurrent>(empty)
    }

    @Test
    fun `a pinned schemaVersion 1 row is applied including json palette fallback`() {
        val json = """
            {
              "schemaVersion": 1,
              "mode": "DARK",
              "iconStyle": "SYSTEM",
              "iconPackPackage": "",
              "updatedAtMs": 42,
              "palette": {
                "background": "#FF0B0C12",
                "surface": "#FF161821",
                "primary": "#FF00E5FF",
                "secondary": "#FFE840FF",
                "onBackground": "#FFF5F7FF",
                "muted": "#FF9AA3B5",
                "danger": "#FFFF6B8A",
                "stale": "#FFFFC14D",
                "tile": "#CC1A1D2B"
              }
            }
        """.trimIndent()
        val decision = client.evaluate(
            row(
                mapOf(
                    FleetThemeContract.Columns.SCHEMA_VERSION to "1",
                    FleetThemeContract.Columns.JSON to json,
                    FleetThemeContract.Columns.PRIMARY to "#FF112233",
                ),
            ),
        )
        val applied = assertIs<FleetThemeDecision.Apply>(decision)
        assertEquals(FleetThemeMode.DARK, applied.snapshot.mode)
        assertEquals(42L, applied.snapshot.updatedAtMs)
        assertEquals("#FF112233", applied.snapshot.palette.primary)
        assertEquals("#FF0B0C12", applied.snapshot.palette.background)
        assertEquals("#CC1A1D2B", applied.snapshot.palette.tile)
        assertEquals("#FFFFC14D", applied.snapshot.palette.stale)
    }

    @Test
    fun `json schemaVersion 2 is rejected even when columns look empty`() {
        val json = """{"schemaVersion":2,"mode":"DARK","palette":{"primary":"#FF00E5FF"}}"""
        val decision = client.evaluate(
            row(mapOf(FleetThemeContract.Columns.JSON to json)),
        )
        assertIs<FleetThemeDecision.KeepCurrent>(decision)
    }

    @Test
    fun `unknown mode is still applied when schema and pin pass`() {
        val decision = client.evaluate(
            row(validColumns(extra = mapOf(FleetThemeContract.Columns.MODE to "ULTRAVIOLET"))),
        )
        val applied = assertIs<FleetThemeDecision.Apply>(decision)
        assertNull(applied.snapshot.mode)
        assertEquals("#FF00E5FF", applied.snapshot.palette.primary)
    }
}
