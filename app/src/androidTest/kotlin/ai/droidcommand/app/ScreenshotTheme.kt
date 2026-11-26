package ai.droidcommand.app

import ai.droidcommand.config.ThemeMode
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.ext.junit.rules.ActivityScenarioRule

/**
 * Screenshots in docs/ are dark. The app's theme defaults to [ThemeMode.SYSTEM] and CI emulators run in light mode, so
 * without this every capture came out white. Setting the mode through the app's own settings object (the same call
 * Settings -> Look makes) does not depend on an emulator night-mode command, which differs across API levels.
 *
 * Returns the mode that was in force so the caller can restore it and leave the device as it found it.
 */
internal fun AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>.forceDarkTheme(): ThemeMode {
    var previous = ThemeMode.SYSTEM
    runOnUiThread {
        val settings = activity.appThemeSettings
        previous = settings.state.value.mode
        settings.setMode(ThemeMode.DARK)
    }
    waitForIdle()
    return previous
}

internal fun AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>.restoreTheme(mode: ThemeMode) {
    runOnUiThread { activity.appThemeSettings.setMode(mode) }
    waitForIdle()
}
