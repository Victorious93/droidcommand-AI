package ai.droidcommand.app

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiSelector
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Walks the production [MainActivity] five-tab chrome and writes one PNG
 * per primary screen under the app's external-files `screenshots/` directory
 * (override with the `screenshotDir` instrumentation argument).
 *
 * A fresh install shows first-run; this walk captures that, then skips it
 * and visits Fleet, AI Chat, Models, Settings, Advanced, plus the extra
 * screens still reached from Advanced.
 */
@RunWith(AndroidJUnit4::class)
class PrimaryScreensScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private lateinit var device: UiDevice
    private lateinit var outDir: File

    @Before
    fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        device = UiDevice.getInstance(instrumentation)
        val args = InstrumentationRegistry.getArguments()
        val requested = args.getString("screenshotDir")?.ifBlank { null }
        val candidates = listOfNotNull(
            requested?.let { File(it) },
            File(instrumentation.targetContext.getExternalFilesDir(null), "screenshots"),
            File(instrumentation.targetContext.externalCacheDir, "screenshots"),
        )
        outDir = candidates.first { it.mkdirs() || it.isDirectory }
    }

    @Test
    fun capturePrimaryScreens() {
        val failures = mutableListOf<String>()
        fun step(name: String, block: () -> Unit) {
            try {
                dismissAnr()
                block()
            } catch (t: Throwable) {
                failures.add("$name: ${t.javaClass.simpleName}: ${t.message}")
                try {
                    skipSetupIfShown()
                    tapNav("nav_chat")
                } catch (_: Throwable) {
                }
            }
        }

        step("first-run") {
            skipSetupIfShown(capture = true)
        }
        step("fleet") {
            tapNav("nav_fleet")
            waitForText("Get latest APK")
            waitForText("Refresh", timeoutMs = 20_000)
            snap("fleet")
        }
        step("connect-device") {
            tapNav("nav_fleet")
            waitForText("Refresh", timeoutMs = 20_000)
            compose.onNodeWithTag("fleet_connect_device").performScrollTo().performClick()
            waitForText("Connect a device")
            snap("connect-device")
        }
        step("chat") {
            tapNav("nav_chat")
            waitForText("AI Chat")
            snap("chat")
        }
        step("models") {
            tapNav("nav_models")
            waitForText("Current model")
            snap("models")
        }
        step("settings") {
            tapNav("nav_settings")
            waitForText("Look")
            snap("settings")
        }
        step("advanced") {
            tapNav("nav_advanced")
            waitForText("These tools can change this phone")
            snap("advanced")
        }
        step("advanced-shizuku") {
            tapNav("nav_advanced")
            waitForText("These tools can change this phone")
            compose.onNodeWithText("Start on boot").performScrollTo()
            waitForText("Start on boot")
            snap("advanced-shizuku")
        }
        step("tools") {
            openFromAdvanced("Extra tools")
            waitForText("Security Tools")
            snap("tools")
        }
        step("notes") {
            openFromAdvanced("Brain")
            waitForText("Vault")
            snap("notes")
            snap("brain")
        }
        step("tools-metasploit") {
            openFromAdvanced("Extra tools")
            waitForText("Metasploit")
            compose.onNodeWithText("Metasploit").performScrollTo().performClick()
            waitForText("VictorSuite (Termux + proot Kali)")
            snap("tools-metasploit")
        }
        step("tool-approval") {
            compose.onNodeWithText("auxiliary/scanner/portscan/tcp").performClick()
            compose.onNodeWithText("Target host (required, authorized target only)").performTextInput("127.0.0.1")
            device.pressBack()
            compose.waitForIdle()
            compose.onNodeWithText("Run Module").performScrollTo().performClick()
            waitForText("Confirm action")
            snap("tool-approval")
            compose.onNodeWithText("Deny").performClick()
            compose.waitForIdle()
        }
        step("memory") {
            openFromAdvanced("Memory")
            waitForText("What DroidCommand AI has remembered")
            snap("memory")
        }
        step("pilot") {
            openFromAdvanced("One-step command")
            waitForText("Runs one approved action")
            snap("pilot")
        }
        step("forge") {
            openFromAdvanced("Multi-step plan")
            waitForText("Give the app a goal")
            snap("forge")
        }
        step("logs") {
            openFromAdvanced("Activity log")
            waitForText("All events")
            snap("logs")
        }
        step("n8n") {
            openFromAdvanced("n8n (self-hosted)")
            waitForText("Connect to your n8n server")
            snap("n8n")
        }
        step("tools-setoolkit") {
            openFromAdvanced("Extra tools")
            waitForText("Social-Engineer Toolkit")
            compose.onNodeWithText("Social-Engineer Toolkit").performScrollTo().performClick()
            waitForText("No Social-Engineer Toolkit program is connected")
            snap("tools-setoolkit")
        }

        if (failures.isNotEmpty()) {
            throw AssertionError("screen walk failures:\n${failures.joinToString("\n")}")
        }
    }

    @Test
    fun captureToolApprovalAndSet() {
        val failures = mutableListOf<String>()
        fun step(name: String, block: () -> Unit) {
            try {
                dismissAnr()
                block()
            } catch (t: Throwable) {
                failures.add("$name: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
        skipSetupIfShown()
        step("tools-metasploit") {
            openFromAdvanced("Extra tools")
            waitForText("Metasploit")
            compose.onNodeWithText("Metasploit").performScrollTo().performClick()
            waitForText("VictorSuite (Termux + proot Kali)")
            snap("tools-metasploit")
        }
        step("tool-approval") {
            compose.onNodeWithText("auxiliary/scanner/portscan/tcp").performClick()
            compose.onNodeWithText("Target host (required, authorized target only)").performTextInput("127.0.0.1")
            compose.waitForIdle()
            compose.onNodeWithText("Run Module").performScrollTo().performClick()
            waitForText("Confirm action")
            snap("tool-approval")
            compose.onNodeWithText("Deny").performClick()
            compose.waitForIdle()
        }
        step("tools-setoolkit") {
            openFromAdvanced("Extra tools")
            waitForText("Social-Engineer Toolkit")
            compose.onNodeWithText("Social-Engineer Toolkit").performScrollTo().performClick()
            waitForText("No Social-Engineer Toolkit program is connected")
            snap("tools-setoolkit")
        }
        if (failures.isNotEmpty()) {
            throw AssertionError("approval/SET walk failures:\n${failures.joinToString("\n")}")
        }
    }

    private fun skipSetupIfShown(capture: Boolean = false) {
        dismissAnr()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("first_run").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithTag("fleet_dashboard").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("AI Chat", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodesWithTag("first_run").fetchSemanticsNodes().isNotEmpty()) {
            waitForText("This app does two things")
            if (capture) snap("first-run")
            compose.onNodeWithTag("first_run_skip").performClick()
            compose.waitForIdle()
            dismissAnr()
        }
    }

    private fun openFromAdvanced(label: String) {
        tapNav("nav_advanced")
        waitForText("These tools can change this phone")
        compose.onNodeWithText(label).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun tapNav(tag: String) {
        dismissAnr()
        compose.onNodeWithTag(tag, useUnmergedTree = true).performClick()
        compose.waitForIdle()
        dismissAnr()
    }

    private fun dismissAnr() {
        val anr = device.findObject(UiSelector().textContains("isn't responding"))
        if (anr.exists()) {
            val wait = device.findObject(UiSelector().text("Wait"))
            if (wait.exists()) wait.click()
        }
        val waitOnly = device.findObject(UiSelector().text("Wait"))
        if (waitOnly.exists() && waitOnly.isEnabled) {
            waitOnly.click()
        }
    }

    private fun waitForText(text: String, timeoutMs: Long = 15_000) {
        compose.waitUntil(timeoutMs) {
            compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun snap(name: String) {
        compose.waitForIdle()
        device.waitForIdle()
        dismissAnr()
        compose.mainClock.autoAdvance = true
        val dest = File(outDir, "$name.png")
        if (dest.exists()) dest.delete()
        val viaDevice = device.takeScreenshot(dest)
        if (!viaDevice || dest.length() < 1_000L) {
            dest.delete()
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            checkNotNull(bitmap) { "uiAutomation.takeScreenshot returned null for $name" }
            FileOutputStream(dest).use { out ->
                val ok = bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                assertTrue("Bitmap.compress failed for $name → $dest", ok)
            }
            bitmap.recycle()
        }
        assertTrue("screenshot $name is empty ($dest, ${dest.length()} bytes)", dest.length() > 1_000L)
    }
}
