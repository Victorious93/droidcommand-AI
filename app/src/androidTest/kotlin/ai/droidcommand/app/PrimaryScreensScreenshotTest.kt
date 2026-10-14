package ai.droidcommand.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Walks the production [MainActivity] bottom bar / mode switcher and writes one PNG
 * per primary screen under `/data/local/tmp/dca-screenshots/` (override with the
 * `screenshotDir` instrumentation argument).
 *
 * This is a real composition check, not a capture-only dump: each step asserts a
 * screen-specific string before taking the shot. Tool backends stay the existing
 * `Null*` executors — Metasploit is opened only far enough to raise the real
 * [ai.droidcommand.app.approval.ApprovalHost] "Confirm action" dialog, then Denied.
 *
 * Host pull (see `scripts/capture-app-screenshots.sh`):
 * `adb pull /data/local/tmp/dca-screenshots docs/images/`
 */
@RunWith(AndroidJUnit4::class)
class PrimaryScreensScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private lateinit var device: UiDevice
    private lateinit var outDir: File

    @Before
    fun setUp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val args = InstrumentationRegistry.getArguments()
        val requested = args.getString("screenshotDir")
        outDir = File(requested?.ifBlank { null } ?: DEFAULT_DIR)
        if (!outDir.mkdirs() && !outDir.isDirectory) {
            outDir = File(InstrumentationRegistry.getInstrumentation().targetContext.externalCacheDir, "screenshots")
            check(outDir.mkdirs() || outDir.isDirectory) { "cannot create screenshot dir: $outDir" }
        }
    }

    @Test
    fun capturePrimaryScreens() {
        waitForText("DroidCommand")
        snap("chat")

        tapNav("nav_tools")
        waitForText("Security Tools")
        snap("tools")

        compose.onNodeWithText("Open notes").performClick()
        waitForText("Vault")
        snap("notes")

        tapNav("nav_tools")
        waitForText("Metasploit")
        compose.onNodeWithText("Metasploit").performClick()
        waitForText("No Metasploit program is connected")
        snap("tools-metasploit")

        compose.onNodeWithText("auxiliary/scanner/portscan/tcp").performClick()
        compose.onNodeWithText("Target host (required, authorized target only)").performTextInput("127.0.0.1")
        device.pressBack()
        compose.waitForIdle()
        compose.onNodeWithText("Run Module").performScrollTo().performClick()
        waitForText("Confirm action")
        snap("tool-approval")
        compose.onNodeWithText("Deny").performClick()
        compose.waitForIdle()

        tapNav("nav_memory")
        waitForText("Persistent Memory")
        snap("memory")

        tapNav("nav_settings")
        waitForText("API keys")
        snap("settings")

        compose.onNodeWithTag("mode_pilot").performClick()
        waitForText("Pilot engine")
        snap("pilot")

        compose.onNodeWithTag("mode_forge").performClick()
        waitForText("Forge engine")
        snap("forge")

        tapNav("nav_logs")
        waitForText("System Logs")
        snap("logs")

        compose.onNodeWithTag("mode_forge").performClick()
        tapNav("nav_tools")
        waitForText("Social-Engineer Toolkit")
        compose.onNodeWithText("Social-Engineer Toolkit").performClick()
        waitForText("No Social-Engineer Toolkit program is connected")
        snap("tools-setoolkit")
    }

    private fun tapNav(tag: String) {
        compose.onNodeWithTag(tag, useUnmergedTree = true).performClick()
        compose.waitForIdle()
    }

    private fun waitForText(text: String, timeoutMs: Long = 15_000) {
        compose.waitUntil(timeoutMs) {
            compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun snap(name: String) {
        compose.waitForIdle()
        device.waitForIdle()
        // One short settle so the last recomposition is on screen; not a retry loop.
        compose.mainClock.autoAdvance = true
        val dest = File(outDir, "$name.png")
        if (dest.exists()) dest.delete()
        val ok = device.takeScreenshot(dest)
        assertTrue("UiDevice.takeScreenshot failed for $name → $dest", ok)
        assertTrue("screenshot $name is empty ($dest)", dest.length() > 1_000L)
    }

    companion object {
        const val DEFAULT_DIR = "/data/local/tmp/dca-screenshots"
    }
}
