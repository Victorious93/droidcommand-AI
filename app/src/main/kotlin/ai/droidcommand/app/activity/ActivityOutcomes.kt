package ai.droidcommand.app.activity

import ai.droidcommand.activity.ActivityRun
import ai.droidcommand.agent.PlanRun
import ai.droidcommand.agent.PlanStatus
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.describe

/**
 * How a finished Pilot instruction ends its Activity row. [stopped] wins over the result: a run the owner
 * stopped is shown as stopped even if the tool that was already in flight went on to finish.
 */
internal fun ActivityRun.endFromResult(toolName: String, result: ToolResult, stopped: Boolean) {
    when {
        stopped -> cancel("Stopped")
        result is ToolResult.Success -> finish("Done: $toolName")
        result is ToolResult.Partial -> finish("Partly done: $toolName (${result.reason})")
        else -> fail(result.describe())
    }
}

/** How a Plan run ends its Activity row, from the status the runner itself reports. */
internal fun ActivityRun.endFromPlan(plan: PlanRun) {
    when (plan.status) {
        PlanStatus.COMPLETED -> finish(plan.summary?.takeIf { it.isNotBlank() } ?: "Plan completed")
        PlanStatus.FAILED -> fail(plan.summary?.takeIf { it.isNotBlank() } ?: "Plan failed")
        PlanStatus.CANCELLED -> cancel("Stopped")
        PlanStatus.RUNNING -> Unit
    }
}
