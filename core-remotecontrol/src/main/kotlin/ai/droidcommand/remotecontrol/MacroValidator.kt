package ai.droidcommand.remotecontrol

/**
 * Dry-run / validation for macros. Never executes a step, never talks to a
 * node, and never bypasses destructive classification.
 */
object MacroValidator {
    fun validate(macro: Macro): MacroValidation {
        val issues = mutableListOf<String>()
        if (macro.steps.isEmpty()) issues += "macro needs at least one step"
        macro.steps.forEachIndexed { index, step ->
            if (step.actionRef.isBlank()) issues += "step ${index + 1} ('${step.id}') has a blank actionRef"
        }
        return if (issues.isEmpty()) {
            MacroValidation.Ok(dryRun(macro))
        } else {
            MacroValidation.Invalid(issues)
        }
    }

    fun dryRun(macro: Macro): MacroDryRun = MacroDryRun(
        macroId = macro.id,
        displayName = macro.displayName,
        trigger = macro.trigger.kind,
        hasDestructiveStep = macro.hasDestructiveStep,
        toolName = macro.toolName(),
        steps = macro.steps.map { step ->
            MacroStepPlan(
                stepId = step.id,
                targetNodeId = step.target.nodeId,
                integrationClass = step.target.integrationClass.name,
                actionRef = step.actionRef,
                args = step.args,
                destructive = ActionRefClassifier.stepIsDestructive(step),
            )
        },
    )
}

sealed class MacroValidation {
    data class Ok(val dryRun: MacroDryRun) : MacroValidation()
    data class Invalid(val issues: List<String>) : MacroValidation()
}

data class MacroDryRun(
    val macroId: String,
    val displayName: String,
    val trigger: MacroTriggerKind,
    val hasDestructiveStep: Boolean,
    val toolName: String,
    val steps: List<MacroStepPlan>,
)

data class MacroStepPlan(
    val stepId: String,
    val targetNodeId: String,
    val integrationClass: String,
    val actionRef: String,
    val args: Map<String, String>,
    val destructive: Boolean,
)
