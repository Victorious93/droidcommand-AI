package ai.droidcommand.app

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The first real launch of the app on an Android runtime. Every `:app` addendum in
 * docs/AUDIT_2026-09-05.md through slice 3 ends with "no device or emulator"; this test
 * is what moves `:app` past ON DISK / UNVERIFIED for the launch path.
 *
 * It launches the production [MainActivity] — which is `@AndroidEntryPoint`, so the real
 * `@HiltAndroidApp` [DroidCommandApplication] graph (Room conversation store, Keystore
 * secrets vault, knowledge graph, tool registry, approval prompt) is constructed and
 * injected exactly as in production. `ActivityScenario.launch` throws if `onCreate`
 * (including `setContent { DroidCommandNavHost() }`) crashes, so reaching RESUMED is a
 * genuine assertion that the Compose tree composes and the DI graph wires up on-device.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityLaunchTest {
    @Test
    fun launchesToResumedWithoutCrashing() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }
}
