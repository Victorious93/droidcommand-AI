package ai.droidcommand.app

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Drives the real System Settings screen on an emulator or an explicitly authorized test device.
 *
 * It changes only Global `window_animation_scale` and a throwaway key `dca_ss_test_key`, and it puts both back through the app's own Restore.
 * Independent readbacks use the shell (`settings get`), not the app's engine, so a bug in the engine cannot hide itself.
 *
 * Precondition (set by the CI workflow with adb, as the shell user): `pm grant ai.droidcommand.app android.permission.WRITE_SECURE_SETTINGS`.
 * The test fails with a clear message when that is missing instead of silently passing.
 */
@RunWith(AndroidJUnit4::class)
class SystemSettingsFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    /** On failure, writes the screen's semantics tree to logcat (tag SSFLOW) so CI logs show what was really on screen. */
    @get:Rule
    val dumpOnFailure = object : TestWatcher() {
        override fun failed(e: Throwable?, description: Description?) {
            runCatching {
                val tree = compose.onRoot(useUnmergedTree = true).printToString(maxDepth = 60)
                Log.e("SSFLOW", "=== FAILED ${description?.methodName}: ${e?.message?.take(300)}")
                tree.lines()
                    .filter { (it.contains("Tag: ") && !it.contains("ss_preset_") && !it.contains("ss_custom")) || (it.contains("Text = ") && !it.contains("ss_curated")) }
                    .map { it.replace(Regex("^[ |\\-]*Node #\\d+ at \\([^)]*\\)px,? ?"), "").trim() }
                    .filter { it.isNotBlank() }
                    .take(110)
                    .forEach { Log.e("SSFLOW", it.take(220)) }
            }
        }
    }

    private lateinit var device: UiDevice
    private lateinit var outDir: File

    @Before
    fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        device = UiDevice.getInstance(instrumentation)
        val requested = InstrumentationRegistry.getArguments().getString("screenshotDir")?.ifBlank { null }
        val candidates = listOfNotNull(
            requested?.let { File(it) },
            File(instrumentation.targetContext.getExternalFilesDir(null), "screenshots-system-settings"),
        )
        outDir = candidates.first { it.mkdirs() || it.isDirectory }
        skipSetupIfShown()
        tapNav("nav_advanced")
        waitForText("These tools can change this phone")
        compose.onNodeWithTag("advanced_system_settings", useUnmergedTree = true).performScrollTo().performClick()
        waitForTag("system_settings_screen")
    }

    private fun shell(cmd: String): String = device.executeShellCommand(cmd).trim()

    private fun globalValue(key: String): String = shell("settings get global $key")

    @Test
    fun everyTabOpensAndShowsHonestStates() {
        snap("01-curated")
        waitForTag("ss_curated_window_animation_scale")
        waitForTag("ss_unsupported_peak_refresh_rate") // API 28 has no refresh-rate keys: shown as unsupported, not hidden or faked
        waitForTag("ss_private_dns")
        for (tab in listOf("Browse", "Advanced", "Modified", "History", "Profiles", "Access")) {
            clickTab(tab)
            compose.waitForIdle()
            snap("tab-${tab.lowercase()}")
        }
        // Access tab: honest statuses, real application id in the commands, remote refusal stated.
        waitForText("adb shell pm grant ai.droidcommand.app android.permission.WRITE_SECURE_SETTINGS")
        waitForTag("ss_remote_note")
        waitForTag("ss_status_standard")
        clickTab("Browse")
        compose.onNodeWithTag("ss_table_global").performScrollTo().performClick()
        compose.onNodeWithTag("ss_browse_load").performScrollTo().performClick()
        compose.waitUntil(60_000) {
            compose.onAllNodesWithTag("ss_browse_error").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithTag("ss_row_window_animation_scale").fetchSemanticsNodes().isNotEmpty()
        }
        snap("browse-global")
        clickTab("Profiles")
        waitForText("Quick UI")
        snap("profiles")
    }

    @Test
    fun editReadbackHistoryRestoreReadback() {
        val original = globalValue("window_animation_scale")
        assertTrue("precondition: the key must exist on this image (got '$original')", original != "null")
        val target = if (original == "0.5") "1" else "0.5"
        waitForTag("ss_value_window_animation_scale")
        snap("10-before")

        // Edit: preview then confirm.
        // performScrollTo only moves the nearest scroller (the horizontal chip row), so swipe the page until the chip is really on screen.
        val chip = "ss_preset_window_animation_scale_$target"
        compose.onNodeWithTag("ss_curated_window_animation_scale").performScrollTo()
        repeat(6) {
            if (compose.onNodeWithTag(chip).getBoundsInRoot().width.value > 0f) return@repeat
            compose.onRoot().performTouchInput { swipeUp(startY = height * 0.75f, endY = height * 0.45f) }
            compose.waitForIdle()
        }
        compose.onNodeWithTag(chip).performClick()
        waitForTag("ss_plan_dialog")
        waitForText("→")
        snap("11-preview")
        assertTrue(
            "the preview must be executable; standard access needs WRITE_SECURE_SETTINGS (adb pm grant)",
            compose.onAllNodesWithTag("ss_plan_apply").fetchSemanticsNodes().isNotEmpty(),
        )
        compose.onNodeWithTag("ss_plan_apply").performClick()
        waitForTag("ss_outcome_dialog")
        waitForText("Stored and read back")
        snap("12-outcome")
        compose.onNodeWithTag("ss_outcome_ok").performClick()

        // Independent readback through the shell.
        assertEquals(target, globalValue("window_animation_scale"))

        // History shows it; Modified shows the recorded original.
        clickTab("Modified")
        waitForTag("ss_modified_window_animation_scale")
        waitForText("Original: $original")
        snap("13-modified")
        clickTab("History")
        waitForText("Restore this change")
        snap("14-history")

        // Restore the change, then read back exactly.
        compose.onAllNodesWithText("Restore this change")[0].performScrollTo().performClick()
        waitForTag("ss_restore_dialog")
        snap("15-restore-preview")
        compose.onNodeWithTag("ss_restore_apply").performClick()
        waitForTag("ss_outcome_dialog")
        compose.onNodeWithTag("ss_outcome_ok").performClick()
        assertEquals("the exact previous text must come back", original, globalValue("window_animation_scale"))
        clickTab("Modified")
        waitForTag("ss_modified_empty")
        snap("16-after-restore")
    }

    @Test
    fun advancedAddIsRestoredByDeletingBecauseItWasAbsent() {
        assertEquals("null", globalValue("dca_ss_test_key")) // absent before
        clickTab("Advanced")
        compose.onNodeWithTag("ss_adv_name").performTextInput("dca_ss_test_key")
        waitForText("Unknown setting")
        compose.onNodeWithTag("ss_adv_value").performTextInput("hello")
        compose.onNodeWithTag("ss_adv_reviewed").performScrollTo().performClick()
        snap("20-advanced-unknown")
        compose.onNodeWithTag("ss_adv_preview").performScrollTo().performClick()
        waitForTag("ss_plan_dialog")
        waitForText("(not set)")
        compose.onNodeWithTag("ss_plan_apply").performClick()
        waitForTag("ss_outcome_dialog")
        compose.onNodeWithTag("ss_outcome_ok").performClick()
        assertEquals("hello", globalValue("dca_ss_test_key"))

        clickTab("History")
        waitForText("Restore this change")
        compose.onAllNodesWithText("Restore this change")[0].performScrollTo().performClick()
        waitForTag("ss_restore_dialog")
        compose.onNodeWithTag("ss_restore_apply").performClick()
        waitForTag("ss_outcome_dialog")
        compose.onNodeWithTag("ss_outcome_ok").performClick()
        // Deleted, not set to an empty string: `settings get` prints "null" only for a missing row, and the listing must not contain the key.
        assertEquals("null", globalValue("dca_ss_test_key"))
        assertTrue(!shell("settings list global").lines().any { it.startsWith("dca_ss_test_key=") })
        snap("21-absent-again")
    }

    private fun skipSetupIfShown() {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("first_run").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithTag("fleet_dashboard").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("AI Chat", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodesWithTag("first_run").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("first_run_skip").performClick()
            compose.waitForIdle()
        }
    }

    /** The tab row scrolls sideways on a small screen, so bring the tab into view before tapping it. */
    private fun clickTab(name: String) {
        compose.onNodeWithTag("ss_tab_$name").performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun tapNav(tag: String) {
        compose.onNodeWithTag(tag, useUnmergedTree = true).performClick()
        compose.waitForIdle()
    }

    private fun waitForTag(tag: String, timeoutMs: Long = 60_000) {
        compose.waitUntil(timeoutMs) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitForText(text: String, timeoutMs: Long = 60_000) {
        compose.waitUntil(timeoutMs) { compose.onAllNodesWithText(text, substring = true, ignoreCase = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun snap(name: String) {
        compose.waitForIdle()
        device.waitForIdle()
        val dest = File(outDir, "$name.png")
        if (dest.exists()) dest.delete()
        if (!device.takeScreenshot(dest) || dest.length() < 1_000L) {
            dest.delete()
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            checkNotNull(bitmap) { "takeScreenshot returned null for $name" }
            FileOutputStream(dest).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        assertTrue("screenshot $name is empty", dest.length() > 1_000L)
    }
}
