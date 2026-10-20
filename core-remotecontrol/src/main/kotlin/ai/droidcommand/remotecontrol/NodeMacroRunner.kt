package ai.droidcommand.remotecontrol

/**
 * Node-local macro runner for the generic command surface.
 *
 * Dispatches **only** to [advertised] ops, runs steps sequentially, and
 * returns per-step results. `onError = abort` (atomic) stops on the first
 * error and reports **partial** results — it does **not** promise rollback.
 * `dryRun` validates params + gates without executing.
 *
 * External-control OFF fails closed with [RemoteErrorCategory.EXTERNAL_CONTROL_DISABLED].
 * A remote trigger never auto-approves: `requiresConfirm` / `destructive`
 * ops return [RemoteErrorCategory.CONFIRMATION_REQUIRED] unless [dryRun].
 */
class NodeMacroRunner(
    private val advertised: List<OpDescriptor>,
    private val externalControlEnabled: Boolean = true,
    private val executeOp: (op: String, params: Map<String, String>) -> NodeStepOutcome = { _, _ ->
        NodeStepOutcome.Ok(message = "stub")
    },
) {
    fun runStep(op: String, params: Map<String, String> = emptyMap(), dryRun: Boolean = false): NodeMacroRunResult =
        run(
            steps = listOf(
                MacroRunStepWire(op = op, params = params),
            ),
            dryRun = dryRun,
        )

    fun run(steps: List<MacroRunStepWire>, dryRun: Boolean = false): NodeMacroRunResult {
        val results = mutableListOf<NodeStepResult>()
        var stopped = false
        for (step in steps) {
            val outcome = evaluate(step, dryRun)
            results += outcome
            if (!outcome.ok && step.onError == FleetRemoteEnvelope.ON_ERROR_ABORT) {
                stopped = true
                break
            }
        }
        return NodeMacroRunResult(
            ok = results.isNotEmpty() && results.all { it.ok },
            atomicStopped = stopped,
            dryRun = dryRun,
            steps = results,
        )
    }

    private fun evaluate(step: MacroRunStepWire, dryRun: Boolean): NodeStepResult {
        if (!externalControlEnabled) {
            return NodeStepResult(
                op = step.op,
                ok = false,
                category = RemoteErrorCategory.EXTERNAL_CONTROL_DISABLED,
                message = RemoteErrorCategory.EXTERNAL_CONTROL_DISABLED.wire,
                executed = false,
            )
        }
        if (step.params.size > FleetRemoteEnvelope.MAX_ARGS) {
            return NodeStepResult(
                op = step.op,
                ok = false,
                category = RemoteErrorCategory.INVALID_PARAMETERS,
                message = "params exceeds ${FleetRemoteEnvelope.MAX_ARGS} entries",
                executed = false,
            )
        }
        val descriptor = advertised.find { it.name == step.op }
            ?: return NodeStepResult(
                op = step.op,
                ok = false,
                category = RemoteErrorCategory.UNSUPPORTED_OP,
                message = "op '${step.op}' is not advertised on this node",
                executed = false,
            )
        if (!descriptor.remoteAllowed) {
            return NodeStepResult(
                op = step.op,
                ok = false,
                category = RemoteErrorCategory.CAPABILITY_OFF,
                message = RemoteErrorCategory.CAPABILITY_OFF.wire,
                executed = false,
            )
        }
        val needsConfirm = descriptor.requiresConfirm || descriptor.kind == OpKind.DESTRUCTIVE
        if (dryRun) {
            return NodeStepResult(
                op = step.op,
                ok = true,
                category = null,
                message = if (needsConfirm) "dry-run: confirmation would be required" else "dry-run ok",
                executed = false,
            )
        }
        if (needsConfirm) {
            return NodeStepResult(
                op = step.op,
                ok = false,
                category = RemoteErrorCategory.CONFIRMATION_REQUIRED,
                message = RemoteErrorCategory.CONFIRMATION_REQUIRED.wire,
                executed = false,
            )
        }
        return when (val executed = executeOp(step.op, step.params)) {
            is NodeStepOutcome.Ok -> NodeStepResult(
                op = step.op,
                ok = true,
                message = executed.message,
                executed = true,
            )
            is NodeStepOutcome.Err -> NodeStepResult(
                op = step.op,
                ok = false,
                category = executed.category,
                message = executed.message,
                executed = false,
            )
        }
    }
}

sealed class NodeStepOutcome {
    data class Ok(val message: String? = null) : NodeStepOutcome()
    data class Err(
        val category: RemoteErrorCategory,
        val message: String = category.wire,
    ) : NodeStepOutcome()
}

data class NodeStepResult(
    val op: String,
    val ok: Boolean,
    val category: RemoteErrorCategory? = null,
    val message: String? = null,
    val executed: Boolean,
)

data class NodeMacroRunResult(
    val ok: Boolean,
    val atomicStopped: Boolean,
    val dryRun: Boolean,
    val steps: List<NodeStepResult>,
)
