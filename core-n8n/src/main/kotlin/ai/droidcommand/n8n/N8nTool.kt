package ai.droidcommand.n8n

import ai.droidcommand.agent.PermissionCategory
import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolJsonType
import ai.droidcommand.agent.ToolParameter
import ai.droidcommand.agent.ToolParameterSchema
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolSpec

/**
 * n8n tools the AI / Kai can call. Both stay registered, but [aiToolsEnabled]
 * is off until the owner turns it on. [ToolSpec.requiresConfirmation] is
 * always true, so [ai.droidcommand.security.SecureToolExecutor] still asks
 * before every run even after the gate is on.
 */
object N8nTool {
    const val LIST = "n8n_list_workflows"
    const val RUN = "n8n_run_workflow"
    const val GATE_OFF = "n8n tools are off. Turn them on under Advanced → n8n. This phone still asks before every run."
}

class N8nListWorkflowsTool(
    private val client: N8nClient,
    private val serverUrl: () -> String,
    private val aiToolsEnabled: () -> Boolean,
) : Tool {
    override val spec = ToolSpec(
        name = N8nTool.LIST,
        description = "Lists workflows on the owner's self-hosted n8n server. Off until the owner turns on n8n AI tools. Always asks before running.",
        securityLevel = SecurityLevel.SENSITIVE,
        permissionCategory = PermissionCategory.NETWORK,
        parameterSchema = ToolParameterSchema(),
    )

    override fun execute(input: Map<String, String>): ToolResult {
        if (!aiToolsEnabled()) return ToolResult.Failure(N8nTool.GATE_OFF)
        val parsed = N8nServerUrl.parse(serverUrl())
        val url = (parsed as? N8nUrlParse.Ok)?.url
            ?: return ToolResult.Failure((parsed as N8nUrlParse.Rejected).reason)
        return when (val outcome = client.listWorkflows(url)) {
            is N8nOutcome.Ok -> {
                if (outcome.value.isEmpty()) {
                    ToolResult.Success("n8n has no workflows yet.")
                } else {
                    ToolResult.Success(
                        outcome.value.joinToString("\n") { row ->
                            val hook = if (row.canTrigger) "webhook ${row.webhook!!.path}" else "no webhook"
                            val on = if (row.active) "on" else "off"
                            "${row.name} (id ${row.id}, $on, $hook)"
                        },
                    )
                }
            }
            is N8nOutcome.Failed -> ToolResult.Failure(outcome.words)
        }
    }
}

class N8nRunWorkflowTool(
    private val client: N8nClient,
    private val serverUrl: () -> String,
    private val aiToolsEnabled: () -> Boolean,
) : Tool {
    override val spec = ToolSpec(
        name = N8nTool.RUN,
        description = "Runs one webhook workflow on the owner's self-hosted n8n server. Inputs: workflow_id or name, and json. Off until the owner turns on n8n AI tools. Always asks before running.",
        securityLevel = SecurityLevel.SENSITIVE,
        permissionCategory = PermissionCategory.NETWORK,
        parameterSchema = ToolParameterSchema(
            parameters = listOf(
                ToolParameter("workflow_id", ToolJsonType.STRING, "n8n workflow id"),
                ToolParameter("name", ToolJsonType.STRING, "workflow name if the id is not known"),
                ToolParameter("json", ToolJsonType.OBJECT, "JSON object to send to the webhook"),
            ),
        ),
    )

    override fun execute(input: Map<String, String>): ToolResult {
        if (!aiToolsEnabled()) return ToolResult.Failure(N8nTool.GATE_OFF)
        val parsed = N8nServerUrl.parse(serverUrl())
        val url = (parsed as? N8nUrlParse.Ok)?.url
            ?: return ToolResult.Failure((parsed as N8nUrlParse.Rejected).reason)
        val id = input["workflow_id"]?.trim().orEmpty()
        val name = input["name"]?.trim().orEmpty()
        if (id.isEmpty() && name.isEmpty()) {
            return ToolResult.Failure("Say which workflow to run (workflow_id or name).")
        }
        val jsonBody = input["json"] ?: input["body"] ?: "{}"
        return when (val listed = client.listWorkflows(url)) {
            is N8nOutcome.Failed -> ToolResult.Failure(listed.words)
            is N8nOutcome.Ok -> {
                val match = listed.value.firstOrNull { it.id == id } ?: listed.value.firstOrNull { it.name.equals(name, ignoreCase = true) }
                    ?: return ToolResult.Failure("No n8n workflow matches that id or name.")
                when (val ran = client.triggerWebhook(url, match, jsonBody)) {
                    is N8nOutcome.Ok -> ToolResult.Success(ran.words)
                    is N8nOutcome.Failed -> ToolResult.Failure(ran.words)
                }
            }
        }
    }
}
