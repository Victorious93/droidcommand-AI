package ai.droidcommand.app

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in normal-use soak, NOT part of the mandatory emulator gate: it cycles the five primary tabs one step at a time
 * (each step waits for the UI to be idle and for the destination's text) for `-e soakMinutes N` minutes. A crash kills the
 * instrumented process, so a run that finishes with `OK (1 test)` and `INSTRUMENTATION_CODE -1` (scripts/ci/verify-
 * instrumentation.py) means no crash or hung screen over the whole period. Skipped (assumption) when soakMinutes is not given.
 *
 * Random-touch `monkey` was tried first and is the wrong tool on a software-emulated device: it queues input faster than
 * the UI can draw, and the system reported input-dispatch ANRs while the main thread was busy composing (docs/CRASH_REPAIR_REPORT.md).
 */
@RunWith(AndroidJUnit4::class)
class SoakTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun cyclesPrimaryTabsForTheRequestedMinutes() {
        val minutes = InstrumentationRegistry.getArguments().getString("soakMinutes")?.toLongOrNull()
        assumeTrue("soak not requested (pass -e soakMinutes N)", minutes != null && minutes > 0)
        val deadline = System.currentTimeMillis() + minutes!! * 60_000
        val tabs = listOf(
            "nav_fleet" to "Refresh",
            "nav_chat" to "AI Chat",
            "nav_models" to "Current model",
            "nav_settings" to "Look",
            "nav_advanced" to "These tools can change this phone",
        )
        var steps = 0
        ActivityScenario.launch(MainActivity::class.java).use {
            waitForAny("first_run", "fleet_dashboard", "AI Chat")
            if (compose.onAllNodesWithTag("first_run").fetchSemanticsNodes().isNotEmpty()) {
                compose.onNodeWithTag("first_run_skip").performClick()
                compose.waitForIdle()
            }
            while (System.currentTimeMillis() < deadline) {
                for ((tag, text) in tabs) {
                    compose.onNodeWithTag(tag, useUnmergedTree = true).performClick()
                    compose.waitForIdle()
                    compose.waitUntil(60_000) { hasText(text) }
                    steps++
                }
            }
        }
        assertTrue("no tab step completed", steps >= tabs.size)
    }

    private fun hasText(text: String): Boolean = try {
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    } catch (_: IllegalStateException) {
        false
    }

    private fun waitForAny(vararg tagsOrTexts: String) {
        compose.waitUntil(60_000) {
            try {
                tagsOrTexts.any {
                    compose.onAllNodesWithTag(it).fetchSemanticsNodes().isNotEmpty() || hasText(it)
                }
            } catch (_: IllegalStateException) {
                false
            }
        }
    }
}
