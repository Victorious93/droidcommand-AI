package ai.droidcommand.app

import android.graphics.Bitmap
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
import androidx.test.uiautomator.UiSelector
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Walks the production [MainActivity] bottom bar / mode switcher and writes one PNG
 * per primary screen under the app's external-files `screenshots/` directory
 * (override with the `screenshotDir` instrumentation argument). `/data/local/tmp`
 * is not writable from the app UID on API 28.
 *
 * This is a real composition check, not a capture-only dump: each step asserts a
 * screen-specific string before taking the shot. Tool backends stay the existing
 * `Null*` executors — Metasploit is opened only far enough to raise the real
 * [ai.droidcommand.app.approval.ApprovalHost] "Confirm action" dialog, then Denied.
 *
 * Host pull (see `scripts/capture-app-screenshots.sh`):
 * `adb pull /sdcard/Android/data/ai.droidcommand.app/files/screenshots docs/images/`
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
                    tapNav("nav_chat")
                } catch (_: Throwable) {
                }
            }
        }

        step("chat") {
            waitForText("DroidCommand")
            snap("chat")
        }
        step("tools") {
            tapNav("nav_tools")
            waitForText("Security Tools")
            snap("tools")
        }
        step("notes") {
            tapNav("nav_tools")
            waitForText("Security Tools")
            compose.onNodeWithText("Open notes").performScrollTo().performClick()
            waitForText("Vault")
            snap("notes")
        }
        step("tools-metasploit") {
            tapNav("nav_tools")
            waitForText("Metasploit")
            compose.onNodeWithText("Metasploit").performScrollTo().performClick()
            waitForText("No Metasploit program is connected")
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
            tapNav("nav_memory")
            waitForText("Persistent Memory")
            snap("memory")
        }
        step("settings") {
            tapNav("nav_settings")
            waitForText("API keys")
            snap("settings")
        }
        step("pilot") {
            compose.onNodeWithTag("mode_pilot").performClick()
            waitForText("Pilot engine")
            snap("pilot")
        }
        step("forge") {
            compose.onNodeWithTag("mode_forge").performClick()
            waitForText("Forge engine")
            snap("forge")
        }
        step("logs") {
            tapNav("nav_logs")
            waitForText("System Logs")
            snap("logs")
        }
        step("tools-setoolkit") {
            tapNav("nav_tools")
            waitForText("Social-Engineer Toolkit")
            compose.onNodeWithText("Social-Engineer Toolkit").performScrollTo().performClick()
            waitForText("No Social-Engineer Toolkit program is connected")
            snap("tools-setoolkit")
        }

        if (failures.isNotEmpty()) {
            throw AssertionError("screen walk failures:\n${failures.joinToString("\n")}")
        }
    }

    /**
     * Narrower walk for the two screens the full [capturePrimaryScreens] walk
     * often loses on a TCG emulator (ANR / click timeout after Notes). Same
     * Null* backends — Metasploit is opened only far enough to raise
     * [ai.droidcommand.app.approval.ApprovalHost], then Denied.
     */
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
        step("tools-metasploit") {
            tapNav("nav_tools")
            waitForText("Metasploit")
            compose.onNodeWithText("Metasploit").performScrollTo().performClick()
            waitForText("No Metasploit program is connected")
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
            tapNav("nav_tools")
            waitForText("Social-Engineer Toolkit")
            compose.onNodeWithText("Social-Engineer Toolkit").performScrollTo().performClick()
            waitForText("No Social-Engineer Toolkit program is connected")
            snap("tools-setoolkit")
        }
        if (failures.isNotEmpty()) {
            throw AssertionError("approval/SET walk failures:\n${failures.joinToString("\n")}")
        }
    }

    private fun tapNav(tag: String) {
        dismissAnr()
        compose.onNodeWithTag(tag, useUnmergedTree = true).performClick()
        compose.waitForIdle()
        dismissAnr()
    }

    /**
     * TCG emulators on this host often raise "System UI isn't responding" while
     * Hilt/Room first-compose. Click Wait, never Close app.
     */
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
