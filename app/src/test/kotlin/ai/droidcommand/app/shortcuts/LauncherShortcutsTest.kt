package ai.droidcommand.app.shortcuts

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression for the v0.1.4 to v0.1.6 launch crash: `MainActivity.onCreate` called `ShortcutManager.setDynamicShortcuts`
 * with the ids `chat`, `toolbox` and `notes`, which `res/xml/shortcuts.xml` already declares as MANIFEST shortcuts.
 * The platform refuses that with `IllegalArgumentException: Manifest shortcut ID=notes may not be manipulated via APIs`,
 * so every cold start died. Nothing at compile time connects the two files, and the JVM cannot call ShortcutManager,
 * so this pins the contract from the sources instead (the emulator suite StartupResilienceTest covers the real launch).
 */
class LauncherShortcutsTest {
    private val moduleDir: File = listOf(File("."), File("app")).first { File(it, "src/main/AndroidManifest.xml").isFile }

    private val manifestShortcuts: Map<String, String> by lazy {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = factory.newDocumentBuilder().parse(File(moduleDir, "src/main/res/xml/shortcuts.xml"))
        val ns = "http://schemas.android.com/apk/res/android"
        val shortcuts = doc.getElementsByTagName("shortcut")
        (0 until shortcuts.length).associate { index ->
            val shortcut = shortcuts.item(index) as Element
            val intent = shortcut.getElementsByTagName("intent").item(0) as Element
            shortcut.getAttributeNS(ns, "shortcutId") to intent.getAttributeNS(ns, "action")
        }
    }

    @Test
    fun everyQuickActionIsAManifestShortcutWithTheSameIntentAction() {
        assertEquals(QuickAction.entries.associate { it.shortcutId to it.intentAction }, manifestShortcuts)
    }

    @Test
    fun theAppNeverPublishesDynamicShortcuts() {
        // The three launcher shortcuts are the manifest ones above. A dynamic shortcut that reuses one of their ids
        // crashes the app at launch on every Android version that has shortcuts (7.1+). If dynamic shortcuts are ever
        // needed, give them ids outside QuickAction's and relax this test deliberately.
        val forbidden = Regex("""setDynamicShortcuts|addDynamicShortcuts|updateShortcuts|pushDynamicShortcut|dynamicShortcuts\s*=|ShortcutInfo\.Builder|ShortcutInfoCompat""")
        val offenders = File(moduleDir, "src/main/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { forbidden.containsMatchIn(it.readText()) }
            .map { it.path }
            .toList()
        assertTrue(offenders.isEmpty(), "dynamic shortcut APIs used in: $offenders")
    }
}
