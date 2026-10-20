package ai.droidcommand.remotecontrol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NodeMacroRunnerTest {
    private val advertised = listOf(
        OpDescriptor("status.get", OpKind.READ, remoteAllowed = true, requiresConfirm = false),
        OpDescriptor("scan.run", OpKind.ACTION, remoteAllowed = true, requiresConfirm = true),
        OpDescriptor("wipe.device", OpKind.DESTRUCTIVE, remoteAllowed = true, requiresConfirm = true),
        OpDescriptor("local.only", OpKind.READ, remoteAllowed = false, requiresConfirm = false),
    )

    @Test
    fun `dryRun validates advertised ops without executing`() {
        var executed = 0
        val runner = NodeMacroRunner(advertised) { _, _ ->
            executed += 1
            NodeStepOutcome.Ok()
        }
        val result = runner.run(
            listOf(
                MacroRunStepWire(op = "status.get", params = mapOf("k" to "v")),
                MacroRunStepWire(op = "scan.run"),
            ),
            dryRun = true,
        )
        assertTrue(result.ok)
        assertTrue(result.dryRun)
        assertFalse(result.atomicStopped)
        assertEquals(2, result.steps.size)
        assertTrue(result.steps.all { !it.executed && it.ok })
        assertEquals(0, executed)
    }

    @Test
    fun `atomic abort stops on first error and reports partial without rollback`() {
        val runner = NodeMacroRunner(advertised)
        val result = runner.run(
            listOf(
                MacroRunStepWire(op = "missing.op", onError = FleetRemoteEnvelope.ON_ERROR_ABORT),
                MacroRunStepWire(op = "status.get"),
            ),
        )
        assertFalse(result.ok)
        assertTrue(result.atomicStopped)
        assertEquals(1, result.steps.size)
        assertEquals(RemoteErrorCategory.UNSUPPORTED_OP, result.steps.single().category)
        assertFalse(result.steps.single().executed)
    }

    @Test
    fun `continue keeps going after an error`() {
        val runner = NodeMacroRunner(advertised)
        val result = runner.run(
            listOf(
                MacroRunStepWire(op = "missing.op", onError = FleetRemoteEnvelope.ON_ERROR_CONTINUE),
                MacroRunStepWire(op = "status.get"),
            ),
        )
        assertFalse(result.ok)
        assertFalse(result.atomicStopped)
        assertEquals(2, result.steps.size)
        assertEquals(RemoteErrorCategory.UNSUPPORTED_OP, result.steps[0].category)
        assertTrue(result.steps[1].ok)
        assertTrue(result.steps[1].executed)
    }

    @Test
    fun `requiresConfirm is confirmation_required and never auto-executes`() {
        val runner = NodeMacroRunner(advertised)
        val result = runner.runStep("scan.run")
        assertFalse(result.ok)
        assertEquals(RemoteErrorCategory.CONFIRMATION_REQUIRED, result.steps.single().category)
        assertFalse(result.steps.single().executed)
    }

    @Test
    fun `remoteAllowed false is capability_off`() {
        val runner = NodeMacroRunner(advertised)
        val result = runner.runStep("local.only")
        assertEquals(RemoteErrorCategory.CAPABILITY_OFF, result.steps.single().category)
    }

    @Test
    fun `external control off is external_control_disabled`() {
        val runner = NodeMacroRunner(advertised, externalControlEnabled = false)
        val result = runner.runStep("status.get")
        assertEquals(RemoteErrorCategory.EXTERNAL_CONTROL_DISABLED, result.steps.single().category)
        assertFalse(result.steps.single().executed)
    }
}
