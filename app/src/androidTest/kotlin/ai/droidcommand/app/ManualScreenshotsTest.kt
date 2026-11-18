package ai.droidcommand.app

import ai.droidcommand.app.bots.HelperBot
import ai.droidcommand.app.bots.HelperBots
import ai.droidcommand.config.AssistantCapabilitySettings
import ai.droidcommand.config.FileAssistantCapabilitySettingsStore
import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
 * Screenshots for `docs/MANUAL.md`'s Bot maker and MCP chapters. Seeds two sample bots and turns on the Bot maker and
 * Linux environment switches in this app's own storage first (nothing leaves the emulator), then walks the screens.
 * Same output directory and pull step as [PrimaryScreensScreenshotTest]. Every step is isolated, so one screen
 * failing does not lose the others.
 */
@RunWith(AndroidJUnit4::class)
class ManualScreenshotsTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private lateinit var device: UiDevice
    private lateinit var outDir: File

    private val researchId = "11111111-1111-1111-1111-111111111111"
    private val writerId = "22222222-2222-2222-2222-222222222222"

    @Before
    fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        device = UiDevice.getInstance(instrumentation)
        val target = instrumentation.targetContext
        val requested = InstrumentationRegistry.getArguments().getString("screenshotDir")?.ifBlank { null }
        outDir = listOfNotNull(
            requested?.let { File(it) },
            File(target.getExternalFilesDir(null), "screenshots"),
            File(target.externalCacheDir, "screenshots"),
        ).first { it.mkdirs() || it.isDirectory }
        FileAssistantCapabilitySettingsStore(target.filesDir.toPath().resolve("assistant-capabilities.settings"))
            .save(AssistantCapabilitySettings(botMakerEnabled = true, linuxEnvironmentEnabled = true))
        target.getSharedPreferences("helper_bots", android.content.Context.MODE_PRIVATE).edit()
            .putString(
                "bots.v1",
                HelperBots.encode(
                    listOf(
                        HelperBot(researchId, "Research", "Look things up on the web and summarise them in five bullets.", setOf("web_search", "ask_ai"), talksTo = setOf(writerId), sandbox = true),
                        HelperBot(writerId, "Writer", "Turn notes into a short, friendly message."),
                    ),
                ),
            ).commit()
    }

    @Test
    fun captureBotMakerAndMcp() {
        val failures = mutableListOf<String>()
        fun step(name: String, block: () -> Unit) {
            try {
                dismissAnr()
                block()
            } catch (t: Throwable) {
                failures.add("$name: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
        step("first-run") { skipSetupIfShown() }
        step("advanced-mcp") {
            tapNav("nav_advanced")
            waitForText("These tools can change this phone")
            compose.onNodeWithText("Check servers and load tools", substring = true).performScrollTo()
            snap("advanced-mcp")
        }
        step("bot-maker") {
            tapNav("nav_advanced")
            waitForText("These tools can change this phone")
            compose.onNodeWithTag("advanced_bot_maker", useUnmergedTree = true).performScrollTo().performClick()
            waitForText("Your bots")
            snap("bot-maker")
        }
        step("bot-maker-editor") {
            compose.onNodeWithTag("bot_new_button", useUnmergedTree = true).performScrollTo().performClick()
            waitForText("1 · Basics")
            snap("bot-maker-editor")
        }
        step("bot-maker-tools") {
            compose.onNodeWithText("2 · Tools").performScrollTo()
            compose.onNodeWithText("Web & AI").performScrollTo().performClick()
            compose.waitForIdle()
            snap("bot-maker-tools")
        }
        step("bot-maker-peers") {
            // Scrolling to the next section's title leaves the whole "Talks to other bots" section above the bottom bar.
            compose.onNodeWithText("4 · Own sandbox").performScrollTo()
            snap("bot-maker-peers")
        }
        step("bot-maker-sandbox") {
            // Scroll to the switch row itself so the whole section is above the bottom bar.
            compose.onNodeWithText("Own Linux sandbox").performScrollTo()
            snap("bot-maker-sandbox")
        }
        if (failures.isNotEmpty()) throw AssertionError("manual screenshot failures:\n${failures.joinToString("\n")}")
    }

    private fun skipSetupIfShown() {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("first_run").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithTag("nav_advanced", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodesWithTag("first_run").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("first_run_skip").performClick()
            compose.waitForIdle()
        }
    }

    private fun tapNav(tag: String) {
        dismissAnr()
        compose.onNodeWithTag(tag, useUnmergedTree = true).performClick()
        compose.waitForIdle()
        dismissAnr()
    }

    private fun dismissAnr() {
        val wait = device.findObject(UiSelector().text("Wait"))
        if (device.findObject(UiSelector().textContains("isn't responding")).exists() && wait.exists()) wait.click()
    }

    private fun waitForText(text: String, timeoutMs: Long = 20_000) {
        compose.waitUntil(timeoutMs) {
            compose.onAllNodesWithText(text, substring = true, ignoreCase = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun snap(name: String) {
        compose.waitForIdle()
        device.waitForIdle()
        dismissAnr()
        val dest = File(outDir, "$name.png")
        if (dest.exists()) dest.delete()
        if (!device.takeScreenshot(dest) || dest.length() < 1_000L) {
            dest.delete()
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            checkNotNull(bitmap) { "uiAutomation.takeScreenshot returned null for $name" }
            FileOutputStream(dest).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        assertTrue("screenshot $name is empty", dest.length() > 1_000L)
    }
}
