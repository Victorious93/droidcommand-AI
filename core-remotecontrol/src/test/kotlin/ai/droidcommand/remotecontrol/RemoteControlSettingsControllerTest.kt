package ai.droidcommand.remotecontrol

import ai.droidcommand.security.FleetIntegrationClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RemoteControlSettingsControllerTest {
    private val now = 9_000L
    private val store = InMemoryRemoteControlStore()
    private val controller = RemoteControlSettingsController(
        store,
        RemoteCommandDispatcher(store, ReplayGuard(nowMs = { now })),
    )

    @Test
    fun `put and remove mappings targets and macros`() {
        controller.putMapping(mapping(id = "a", smsCommandId = "a"))
        controller.putTarget(
            TargetDevice(
                nodeId = "node-1",
                displayName = "Phone",
                integrationClass = FleetIntegrationClass.TERMUX_IPC,
                packageName = "com.termux",
            ),
        )
        controller.putMacro(sampleMacro(id = "m", voicePhrase = "run bedtime"))
        val snap = controller.snapshot()
        assertEquals(listOf("a"), snap.mappings.map { it.id })
        assertEquals(listOf("node-1"), snap.targets.map { it.nodeId })
        assertEquals(listOf("m"), snap.macros.map { it.id })

        controller.removeMapping("a")
        controller.removeTarget("node-1")
        controller.removeMacro("m")
        val empty = controller.snapshot()
        assertTrue(empty.mappings.isEmpty())
        assertTrue(empty.targets.isEmpty())
        assertTrue(empty.macros.isEmpty())
    }

    @Test
    fun `pairings and external-control persist on the store`() {
        assertEquals(false, controller.snapshot().externalControlEnabled)
        controller.setExternalControlEnabled(true)
        controller.putPairing(pairing())
        val snap = controller.snapshot()
        assertEquals(true, snap.externalControlEnabled)
        assertEquals(TEST_SOURCE, snap.pairings.single().sourceNodeRef)
        controller.removePairing(TEST_SOURCE)
        controller.setExternalControlEnabled(false)
        assertTrue(controller.snapshot().pairings.isEmpty())
        assertEquals(false, controller.snapshot().externalControlEnabled)
    }

    @Test
    fun `preview voice uses current mappings`() {
        controller.putMapping(mapping(id = "voice-1", voicePhrase = "red balloon", smsCommandId = "rb"))
        assertIs<RemoteDispatchResult.Ready>(controller.previewVoice("Red balloon"))
        assertIs<RemoteDispatchResult.Rejected>(controller.previewVoice("blue balloon"))
    }

    @Test
    fun `dry-run reports destructive steps without executing`() {
        controller.putMacro(sampleMacro(id = "wipe-all", voicePhrase = "wipe the fleet", destructive = true))
        val result = assertIs<MacroValidation.Ok>(controller.dryRunMacro("wipe-all"))
        assertTrue(result.dryRun.hasDestructiveStep)
        assertEquals(RemoteControlToolNames.DESTRUCTIVE, result.dryRun.toolName)
        assertEquals(1, result.dryRun.steps.size)
    }
}
