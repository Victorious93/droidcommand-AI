package ai.droidcommand.execution

import ai.droidcommand.agent.CapabilityId
import ai.droidcommand.agent.ExecutionTargetType

/**
 * The honest stand-in for an [ExecutionTargetType] this environment cannot
 * actually back with anything real — no Android device, Termux, Docker
 * socket, SSH-reachable remote host, or Proxmox API exists here to run
 * `ANDROID`/`TERMUX`/`DOCKER`/`REMOTE_HOST`/`PROXMOX_VM`/`PROXMOX_LXC`
 * against. Mirrors `core-root.NullRootProvider`/`NullRootExecutor`'s
 * already-established "self-documented non-real implementation"
 * precedent: a single class parametrized by [type], rather than six
 * near-duplicate ones. [isHealthy] always reports `false` and [execute]
 * never spawns anything — both are honest signals a real caller can act
 * on, never a fabricated success.
 */
class NullExecutionTarget(
    override val id: String,
    override val type: ExecutionTargetType,
    override val context: ExecutionContext,
    override val availableCapabilities: Set<CapabilityId> = emptySet(),
) : ExecutionTarget {
    override fun isHealthy(): Boolean = false

    override fun execute(argv: List<String>, workingDir: String?, env: Map<String, String>?, timeoutMs: Long): ExecutionResult = ExecutionResult(
        exitCode = -1,
        stdout = "",
        stderr = "Execution target '$id' ($type) has no real backing in this environment",
        timedOut = false,
        target = type,
        verified = false,
    )
}
