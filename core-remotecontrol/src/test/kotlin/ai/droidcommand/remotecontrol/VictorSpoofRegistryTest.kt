package ai.droidcommand.remotecontrol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VictorSpoofRegistryTest {
    private val now = 40_000L
    private val store = InMemoryRemoteControlStore(inboundConfig())
    private val dispatcher = RemoteCommandDispatcher(store, ReplayGuard(nowMs = { now }))

    @Test
    fun `spoof actionRefs are isolated from destructive classification`() {
        for (actionRef in VictorSpoofRegistry.ACTION_REFS) {
            assertFalse(ActionRefClassifier.isDestructive(actionRef, stepFlag = true), actionRef)
        }
        assertTrue(ActionRefClassifier.isDestructive("wipe", stepFlag = false))
        assertTrue(ActionRefClassifier.isDestructive("flash", stepFlag = false))
        assertTrue(ActionRefClassifier.isDestructive("unlock_bootloader", stepFlag = false))
        assertEquals("com.victorious.spoof", VictorSpoofRegistry.PACKAGE)
    }

    @Test
    fun `destructive true on a spoof actionRef is still not gated as wipe`() {
        val ready = assertIs<RemoteDispatchResult.Ready>(
            dispatcher.dispatch(
                envelope(
                    actionRef = "spoof.profile.apply",
                    timestampMs = now,
                    destructive = true,
                ),
                source(),
            ),
        )
        assertEquals("spoof.profile.apply", ready.plan.spoof?.actionRef)
        assertFalse(ready.plan.destructive)
        assertEquals(RemoteControlToolNames.COMMAND, ready.plan.toolName)
        assertEquals(true, ready.plan.advisoryDestructive)
        assertEquals(RemoteControlToolNames.WIPE, mapping(action = RemoteAction.WIPE, destructive = true, smsCommandId = "wipe").toolName())
    }
}
