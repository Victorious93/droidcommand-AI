package ai.droidcommand.app

import android.content.Context
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Mandatory release-gate behaviour tests (see scripts/ci/emulator-smoke.sh, which names each test it requires).
 * They assert what the owner would see (screen text, tabs that respond), not that a PNG was written.
 *
 * Run only on a disposable emulator or device: [survivesCorruptPersistedState] overwrites app state files. It backs
 * every file up first and restores it in `finally`, but a killed run cannot restore anything.
 *
 * A crash anywhere in here (including an exception thrown on a coroutine in a view model) kills the instrumented
 * process; the runner then prints `Process crashed.`, which scripts/ci/verify-instrumentation.py rejects.
 */
@RunWith(AndroidJUnit4::class)
class StartupResilienceTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext

    @Test
    fun survivesCorruptPersistedState() {
        val files = File(context.filesDir.path)
        val victims = listOf(
            File(files, "inference-policy.properties"),
            File(files, "pc-hosts.properties"),
            File(files, "status-tray.json"),
            File(files, "local-models/registry.txt"),
        )
        // Not valid UTF-8, a malformed \\u escape (java.util.Properties throws IllegalArgumentException on it), no newline.
        val garbage = byteArrayOf(0xC3.toByte(), 0x28, 0xFF.toByte(), 0xFE.toByte()) + "policy=\\uZZZZ\u0000{{{\t\t\t".toByteArray()
        val backups = victims.associateWith { if (it.isFile) it.readBytes() else null }
        try {
            victims.forEach { file ->
                file.parentFile?.mkdirs()
                file.writeBytes(garbage)
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                assertEquals(Lifecycle.State.RESUMED, scenario.state)
                skipFirstRunIfShown()
                openTab("nav_chat", "AI Chat")
                openTab("nav_models", "Current model")
                openTab("nav_chat", "AI Chat")
            }
        } finally {
            backups.forEach { (file, original) ->
                if (original != null) file.writeBytes(original) else file.delete()
            }
        }
    }

    @Test
    fun survivesRecreationAndBackgroundResume() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            skipFirstRunIfShown()
            openTab("nav_chat", "AI Chat")

            scenario.recreate()
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            waitForText("AI Chat")

            // Background and come back: the activity is stopped, then started and resumed again, state intact.
            // (A real Home press and relaunch of the process is exercised by scripts/qa/startup-matrix.sh.)
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            waitForText("AI Chat")

            scenario.recreate()
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            waitForText("AI Chat")
        }
    }

    @Test
    fun everyPrimaryTabOpensAndStaysUsable() {
        ActivityScenario.launch(MainActivity::class.java).use {
            skipFirstRunIfShown()
            openTab("nav_fleet", "Refresh")
            openTab("nav_chat", "AI Chat")
            openTab("nav_models", "Current model")
            openTab("nav_settings", "Look")
            openTab("nav_advanced", "These tools can change this phone")
            openTab("nav_chat", "AI Chat")
        }
    }

    /**
     * With VictorLaunch absent (the emulator and most phones) the fleet-theme trust hint must not appear: it is drawn over
     * the bottom tab bar and was shown on every launch because a missing provider was reported as "access denied".
     * Skipped (assumption) where VictorLaunch is installed.
     */
    @Test
    fun noTrustHintCoversTheTabBarWithoutVictorLaunch() {
        assumeTrue(
            "VictorLaunch is installed on this target",
            context.packageManager.resolveContentProvider("com.victorious.launch.theme", 0) == null,
        )
        ActivityScenario.launch(MainActivity::class.java).use {
            skipFirstRunIfShown()
            waitForAny("no screen appeared", "fleet_dashboard", "AI Chat")
            compose.waitForIdle()
            Thread.sleep(2_000)
            assertTrue(
                "the trust hint is showing although VictorLaunch is not installed",
                compose.onAllNodesWithText("Trust installed fleet share apps", substring = true).fetchSemanticsNodes().isEmpty(),
            )
        }
    }

    @Test
    fun repeatedActivityLaunchesStayAlive() {
        repeat(10) { attempt ->
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                assertEquals("launch #${attempt + 1}", Lifecycle.State.RESUMED, scenario.state)
                skipFirstRunIfShown()
                waitForAny("launch #${attempt + 1} showed no screen", "fleet_dashboard", "AI Chat")
            }
        }
    }

    private fun skipFirstRunIfShown() {
        waitForAny("no first-run, fleet or chat screen appeared", "first_run", "fleet_dashboard", "AI Chat")
        if (compose.onAllNodesWithTag("first_run").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("first_run_skip").performClick()
            compose.waitForIdle()
        }
    }

    private fun openTab(tag: String, expectedText: String) {
        compose.onNodeWithTag(tag, useUnmergedTree = true).performClick()
        compose.waitForIdle()
        waitForText(expectedText)
    }

    private fun waitForText(text: String, timeoutMs: Long = 30_000) {
        compose.waitUntil(timeoutMs) { hasText(text) }
        assertTrue(hasText(text))
    }

    /** False (not an error) while there is no Compose hierarchy yet, e.g. right after the activity was stopped and restarted. */
    private fun hasText(text: String): Boolean = try {
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    } catch (_: IllegalStateException) {
        false
    }

    /** Each argument is a test tag or, failing that, visible text. */
    private fun waitForAny(failure: String, vararg tagsOrTexts: String, timeoutMs: Long = 30_000) {
        val found = {
            try {
                tagsOrTexts.any {
                    compose.onAllNodesWithTag(it).fetchSemanticsNodes().isNotEmpty() ||
                        compose.onAllNodesWithText(it, substring = true).fetchSemanticsNodes().isNotEmpty()
                }
            } catch (_: IllegalStateException) {
                false
            }
        }
        compose.waitUntil(timeoutMs) { found() }
        assertTrue(failure, found())
    }
}
