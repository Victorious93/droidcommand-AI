package ai.droidcommand.companion

import ai.droidcommand.agent.Initiator
import ai.droidcommand.agent.PermissionCategory
import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolSpec
import ai.droidcommand.remotecontrol.HackerAiEnvelopeChannel
import ai.droidcommand.remotecontrol.HackerAiEnvelopeTransport

/**
 * Wraps any AIDL companion capability as a [Tool] visible to DCA's
 * [ai.droidcommand.security.SecureToolExecutor] and Forge planner.
 *
 * All companion calls are dispatched with [Initiator.REMOTE], matching the
 * security boundary: these calls cross a process boundary into an independently
 * installed APK, so they are treated as remote inputs in the policy enforcer
 * even though the physical transport is same-device AIDL.
 *
 * If the companion service is not connected at invocation time, [execute]
 * returns [ToolResult.Failure] — the registry health check should prevent
 * the planner from scheduling this tool when the companion is absent, but
 * the runtime check here is the final safety gate.
 *
 * HackerAI talks to [IHackerAIService] (the interface
 * [CompanionRegistry.getHackerAiService] actually returns). It does **not**
 * cast that binder to [ICompanionService] — that cast always failed because
 * the companion exposes `IHackerAIService`, not `ICompanionService`.
 * Commands go through [HackerAiEnvelopeTransport] → `executeEnvelope` /
 * `getTaskResult` with the frozen remote-control envelope v1.1 JSON.
 *
 * @param registry      Live [CompanionRegistry] managed by the :app module.
 * @param capabilityId  One of the IDs advertised by the companion's
 *                      [ICompanionService.listCapabilities].
 * @param spec          Declarative metadata; must include [SecurityLevel.SENSITIVE],
 *                      [PermissionCategory.NETWORK], and [Initiator.REMOTE]
 *                      in [ToolSpec.requiredInitiator].
 */
class CompanionTool(
    private val registry: CompanionRegistry,
    private val capabilityId: String,
    override val spec: ToolSpec,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) : Tool {
    override fun execute(input: Map<String, String>): ToolResult {
        if (capabilityId.startsWith("ai.companion.hackerai.")) {
            return executeHackerAi(input)
        }

        // Pentest-Swarm still only has domain AIDL, not ICompanionService.
        // This slice does not invent a Pentest executeEnvelope path.
        val service: ICompanionService? = when {
            capabilityId.startsWith("ai.companion.pentestswarm.") ->
                registry.getPentestSwarmService() as? ICompanionService
            else -> null
        }

        if (service == null) {
            return ToolResult.Failure(
                "Companion service for capability '$capabilityId' is not connected. " +
                    "Install and launch the companion APK, then retry.",
            )
        }

        val inputJson = input["input"] ?: input.entries
            .joinToString(",", "{", "}") { (k, v) -> "\"$k\":\"$v\"" }

        return runCatching {
            val resultJson = service.executeCapability(capabilityId, inputJson)
            ToolResult.Success(resultJson)
        }.getOrElse { e ->
            ToolResult.Failure("Companion AIDL call failed: ${e.message}", e)
        }
    }

    private fun executeHackerAi(input: Map<String, String>): ToolResult {
        val service = registry.getHackerAiService()
            ?: return ToolResult.Failure(
                "Companion service for capability '$capabilityId' is not connected. " +
                    "Install and launch the companion APK, then retry.",
            )
        val channel = object : HackerAiEnvelopeChannel {
            override val connected: Boolean = true
            override fun executeEnvelope(envelopeJson: String): String =
                service.executeEnvelope(envelopeJson)
            override fun getTaskResult(taskId: String): String =
                service.getTaskResult(taskId)
        }
        val transport = HackerAiEnvelopeTransport(channel, nowMs)
        val taskId = input["taskId"]
        val hasEnvelope = !input["envelope"].isNullOrBlank() ||
            (!input["input"].isNullOrBlank() && input["input"]!!.trim().startsWith("{"))
        if (!taskId.isNullOrBlank() && !hasEnvelope) {
            return transport.pollTask(taskId).toToolResult()
        }
        val envelope = try {
            HackerAiEnvelopeTransport.envelopeFromToolInput(
                input = input,
                nowMs = nowMs(),
                defaultActionRef = HackerAiEnvelopeTransport.defaultActionRefForCapability(capabilityId),
            )
        } catch (e: IllegalArgumentException) {
            return ToolResult.Failure("invalid_parameters: ${e.message ?: "invalid envelope"}", e)
        }
        return transport.submit(envelope).toToolResult()
    }
}

/** Factory helpers for the two known companion tools. */
object CompanionToolFactory {
    fun hackerAiAgentTask(registry: CompanionRegistry): CompanionTool = CompanionTool(
        registry = registry,
        capabilityId = "ai.companion.hackerai.agent_task",
        spec = ToolSpec(
            name = "run_hackerai_agent_task",
            description = "Run a multi-step AI security agent task via the HackerAI companion APK. " +
                "Supports skill selection, evidence-gated finding validation, and doom-loop detection. " +
                "Requires the ai.hackerai.companion APK to be installed.",
            securityLevel = SecurityLevel.SENSITIVE,
            requiresConfirmation = true,
            permissionCategory = PermissionCategory.NETWORK,
            requiredInitiator = setOf(Initiator.REMOTE),
        ),
    )

    fun pentestSwarmRunChain(registry: CompanionRegistry): CompanionTool = CompanionTool(
        registry = registry,
        capabilityId = "ai.companion.pentestswarm.run_chain",
        spec = ToolSpec(
            name = "pentest_swarm_run_chain",
            description = "Run a named exploit chain against a target via the Pentest-Swarm companion APK. " +
                "Requires the ai.pentestswarm.companion APK to be installed and the swarm process running.",
            securityLevel = SecurityLevel.SENSITIVE,
            requiresConfirmation = true,
            permissionCategory = PermissionCategory.NETWORK,
            requiredInitiator = setOf(Initiator.REMOTE),
        ),
    )

    fun pentestSwarmRunPlaybook(registry: CompanionRegistry): CompanionTool = CompanionTool(
        registry = registry,
        capabilityId = "ai.companion.pentestswarm.run_playbook",
        spec = ToolSpec(
            name = "pentest_swarm_run_playbook",
            description = "Run a named pentest playbook via the Pentest-Swarm companion APK. " +
                "Requires the ai.pentestswarm.companion APK to be installed and the swarm process running.",
            securityLevel = SecurityLevel.SENSITIVE,
            requiresConfirmation = true,
            permissionCategory = PermissionCategory.NETWORK,
            requiredInitiator = setOf(Initiator.REMOTE),
        ),
    )
}
