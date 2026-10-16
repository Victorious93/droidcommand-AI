package ai.droidcommand.app

import ai.droidcommand.app.fleet.FleetCatalog
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
    fun `the app does not ask to see every installed package`() {
        val permissions = manifest.children("uses-permission").map { it.androidAttr("name") }
        assertFalse("android.permission.QUERY_ALL_PACKAGES" in permissions)
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
    fun `only the launcher activity is exported`() {
        val exported = listOf("activity", "service", "receiver", "provider")
            .flatMap { application.children(it) }
            .filter { it.androidAttr("exported") == "true" }
            .map { it.androidAttr("name") }
        assertEquals(listOf(".MainActivity"), exported)
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
