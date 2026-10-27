package ai.droidcommand.app

import ai.droidcommand.app.fleet.FleetCatalog
import ai.droidcommand.app.fleet.FleetNodes
import ai.droidcommand.shizuku.ShizukuPackages
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins manifest facts that are easy to break by editing one file and forgetting another, and that no compile step
 * checks. It reads the source manifest and the network security config, so it runs on the JVM without a device.
 */
class ManifestPolicyTest {
    private val androidNs = "http://schemas.android.com/apk/res/android"

    // Gradle runs unit tests from the module directory; the fallback covers a run from the repository root.
    private val moduleDir: File = listOf(File("."), File("app")).first { File(it, "src/main/AndroidManifest.xml").isFile }

    private fun xml(path: String): Element =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File(moduleDir, path)).documentElement

    private val manifest = xml("src/main/AndroidManifest.xml")
    private val application = manifest.children("application").single()

    private fun Element.children(tag: String): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }

    private fun Element.androidAttr(name: String): String? = getAttributeNS(androidNs, name).ifEmpty { null }

    @Test
    fun `every package the fleet screen probes is named in queries`() {
        val declared = manifest.children("queries").single().children("package").map { it.androidAttr("name") }.toSet()
        val probed = FleetCatalog.all.mapNotNull { it.packageName }.toSet()
        // Without an entry, Android 11+ throws NameNotFoundException for an installed app and the screen says "not installed".
        assertTrue(declared.containsAll(probed), "Missing from <queries>: ${probed - declared}")
    }

    @Test
    fun `every package the fleet orchestrator binds or probes is named in queries`() {
        val declared = manifest.children("queries").single().children("package").map { it.androidAttr("name") }.toSet()
        val used = FleetNodes.nodes().mapNotNull { it.packageName }.toSet() + FleetNodes.BOUND_COMPANION_PACKAGES
        assertTrue(declared.containsAll(used), "Missing from <queries>: ${used - declared}")
    }

    @Test
    fun `queries name only catalogued fleet packages besides the speech and TTS intents and Shizuku`() {
        val declared = manifest.children("queries").single().children("package").map { it.androidAttr("name") }.toSet()
        val catalogued = FleetCatalog.queryPackages + ShizukuPackages.queryPackages
        assertEquals(catalogued, declared)
    }

    @Test
    fun `the app does not ask to see every installed package`() {
        val permissions = manifest.children("uses-permission").map { it.androidAttr("name") }
        assertFalse("android.permission.QUERY_ALL_PACKAGES" in permissions)
    }

    @Test
    fun `VictorSuite RUN_COMMAND permission is declared`() {
        val permissions = manifest.children("uses-permission").map { it.androidAttr("name") }
        assertTrue("com.termux.permission.RUN_COMMAND" in permissions)
    }

    @Test
    fun `EDL companion bind permission is used not invented as BIND_EDLCOMMANDER`() {
        val uses = manifest.children("uses-permission").map { it.androidAttr("name") }
        val defined = manifest.children("permission").map { it.androidAttr("name") }
        assertTrue(FleetCatalog.EDLCOMMANDER_BIND_PERMISSION in uses)
        assertEquals("ai.edlcommander.companion.permission.BIND_COMPANION", FleetCatalog.EDLCOMMANDER_BIND_PERMISSION)
        assertEquals("ai.edlcommander.companion.BIND", FleetCatalog.EDLCOMMANDER_BIND_ACTION)
        assertEquals("ai.edlcommander.companion", FleetCatalog.EDLCOMMANDER_PACKAGE)
        assertFalse("ai.droidcommand.permission.BIND_EDLCOMMANDER" in uses)
        assertFalse("ai.droidcommand.permission.BIND_EDLCOMMANDER" in defined)
        assertFalse(defined.any { it?.contains("edl", ignoreCase = true) == true })
    }

    @Test
    fun `Shizuku provider permission and queries are declared`() {
        val permissions = manifest.children("uses-permission").map { it.androidAttr("name") }
        assertTrue(ShizukuPackages.API_PERMISSION in permissions)
        val declared = manifest.children("queries").single().children("package").map { it.androidAttr("name") }.toSet()
        assertTrue(declared.containsAll(ShizukuPackages.queryPackages))
        val provider = application.children("provider").single { it.androidAttr("name") == "rikka.shizuku.ShizukuProvider" }
        assertEquals("true", provider.androidAttr("exported"))
        assertEquals("false", provider.androidAttr("multiprocess"))
        assertEquals("\${applicationId}.shizuku", provider.androidAttr("authorities"))
        assertEquals("android.permission.INTERACT_ACROSS_USERS_FULL", provider.androidAttr("permission"))
    }

    @Test
    fun `a missing microphone does not hide the app`() {
        val microphone = manifest.children("uses-feature").single { it.androidAttr("name") == "android.hardware.microphone" }
        assertEquals("false", microphone.androidAttr("required"))
    }

    @Test
    fun `cloud backup and device transfer are off`() {
        assertEquals("false", application.androidAttr("allowBackup"))
        // Android 9 to 11 read fullBackupContent instead of the extraction rules; the minSdk is 28.
        assertEquals("false", application.androidAttr("fullBackupContent"))
        val rules = xml("src/main/res/xml/data_extraction_rules.xml")
        val domains = listOf("root", "file", "database", "sharedpref", "external")
        for (section in listOf("cloud-backup", "device-transfer")) {
            val excluded = rules.children(section).single().children("exclude").map { it.getAttribute("domain") }
            assertEquals(domains, excluded, "$section must exclude every domain")
            assertTrue(rules.children(section).single().children("include").isEmpty(), "$section must not include anything")
        }
    }

    @Test
    fun `only the launcher activity, fleet-theme receiver, and Shizuku provider are exported`() {
        val exported = listOf("activity", "service", "receiver", "provider")
            .flatMap { application.children(it) }
            .filter { it.androidAttr("exported") == "true" }
            .map { it.androidAttr("name") }
        assertEquals(
            listOf(
                ".MainActivity",
                ".fleet.FleetThemeChangedReceiver",
                "rikka.shizuku.ShizukuProvider",
            ),
            exported,
        )
        val receiver = application.children("receiver").single {
            it.androidAttr("name") == ".fleet.FleetThemeChangedReceiver"
        }
        val actions = receiver.children("intent-filter").single().children("action").map { it.androidAttr("name") }
        assertEquals(listOf("com.victorious.launch.action.FLEET_THEME_CHANGED"), actions)
    }

    @Test
    fun `cleartext HTTP is refused and only the system certificate store is trusted`() {
        assertEquals("@xml/network_security_config", application.androidAttr("networkSecurityConfig"))
        assertTrue(application.androidAttr("usesCleartextTraffic") != "true")
        val config = xml("src/main/res/xml/network_security_config.xml")
        val base = config.children("base-config").single()
        assertEquals("false", base.getAttribute("cleartextTrafficPermitted"))
        val sources = base.children("trust-anchors").single().children("certificates").map { it.getAttribute("src") }
        assertEquals(listOf("system"), sources)
        // A narrow exception for one host would be a domain-config; make adding one a deliberate, reviewed change.
        assertTrue(config.children("domain-config").isEmpty(), "domain-config entries must be reviewed against docs/SECURITY.md")
    }
}
