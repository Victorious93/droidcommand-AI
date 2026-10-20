package ai.droidcommand.app.fleet

import ai.droidcommand.agent.ToolResult
import ai.droidcommand.rootforge.FailureKind
import ai.droidcommand.rootforge.RootForgeNodeRegistry
import ai.droidcommand.rootforge.RootForgeResult
import ai.droidcommand.rootforge.RootForgeTransport
import ai.droidcommand.rootforge.TransportException
import ai.droidcommand.security.FleetNode
import ai.droidcommand.security.FleetNodeTransport
import ai.droidcommand.security.FleetProbeResult
import ai.droidcommand.security.FleetReachability
import ai.droidcommand.termux.TermuxExecutor

// Adapters from the existing per-class clients to FleetNodeTransport. None of them invents a wire protocol: the
// bound-companion one uses core-companion's registry and health checker, the external-node one uses core-rootforge's
// registry and client, and the Termux one reads the existing TermuxExecutor.

/** What the bound-companion transport needs from `core-companion`, so it can be unit-tested without a device. */
interface CompanionLink {
    fun isInstalled(packageName: String): Boolean

    /** Binds if needed and waits briefly. True only when a binder is connected. */
    fun connect(packageName: String): Boolean

    /** The existing `CompanionCapabilityHealthChecker` verdict (HackerAI `healthCheck()`, Pentest-Swarm `isSwarmReady()`). */
    fun domainHealthy(packageName: String): Boolean

    /** True only when the bound service really implements `ICompanionService` (the one with ping and executeCapability). */
    fun exposesCompanionService(packageName: String): Boolean

    fun ping(packageName: String): Boolean

    fun executeCapability(packageName: String, capabilityId: String, inputJson: String): String
}

/**
 * BOUND_COMPANION: `ICompanionService.ping()` when the companion implements it, otherwise the existing health checker.
 *
 * The HackerAI and Pentest-Swarm APK sources implement only their own domain AIDL (`IHackerAIService`,
 * `IPentestSwarmService`), not `ICompanionService`. So today a reachable companion reports no command channel and
 * the screen keeps its command button disabled, saying why. The `executeCapability` path below is real code, covered by
 * fake-link tests, and has never run against a real companion.
 */
class BoundCompanionTransport(private val link: CompanionLink) : FleetNodeTransport {
    override fun probe(node: FleetNode): FleetProbeResult {
        val pkg = node.packageName ?: return FleetProbeResult(FleetReachability.UNKNOWN, "No package to bind")
        if (!link.isInstalled(pkg)) return FleetProbeResult(FleetReachability.OFFLINE, "Companion APK not installed ($pkg)")
        if (!link.connect(pkg)) {
            return FleetProbeResult(
                FleetReachability.OFFLINE,
                "Installed, but the service did not bind. It may not be running, or the signature permission was refused.",
            )
        }
        return if (link.exposesCompanionService(pkg)) {
            if (runCatching { link.ping(pkg) }.getOrDefault(false)) {
                FleetProbeResult(FleetReachability.REACHABLE, "ping() answered (ICompanionService)", commandChannel = true)
            } else {
                FleetProbeResult(FleetReachability.OFFLINE, "Bound, but ICompanionService.ping() failed")
            }
        } else if (link.domainHealthy(pkg)) {
            FleetProbeResult(
                FleetReachability.REACHABLE,
                "Health check passed (domain AIDL). This APK does not implement ICompanionService, so the hub has no command channel.",
            )
        } else {
            FleetProbeResult(FleetReachability.OFFLINE, "Bound, but the companion's health check failed")
        }
    }

    override fun sendCommand(node: FleetNode, capabilityId: String, inputJson: String): ToolResult {
        val pkg = node.packageName ?: return ToolResult.Failure("No package to bind")
        if (!link.connect(pkg)) return ToolResult.Failure("${node.displayName} is not bound")
        if (!link.exposesCompanionService(pkg)) {
            return ToolResult.Failure("${node.displayName} does not implement ICompanionService.executeCapability")
        }
        val response = try {
            link.executeCapability(pkg, capabilityId, inputJson)
        } catch (e: Exception) {
            return ToolResult.Failure("executeCapability failed: ${e.message ?: e::class.simpleName}", e)
        }
        return when {
            OK_TRUE.containsMatchIn(response) -> ToolResult.Success(response)
            OK_FALSE.containsMatchIn(response) -> ToolResult.Failure(response)
            else -> ToolResult.Failure("Unrecognised executeCapability response: ${response.take(200)}")
        }
    }

    private companion object {
        val OK_TRUE = Regex(""""ok"\s*:\s*true""")
        val OK_FALSE = Regex(""""ok"\s*:\s*false""")
    }
}

/**
 * Leftover always-fail [RootForgeTransport] for the SSH-registry adapter and unit tests.
 *
 * **Not** what `:app` `FleetModule` wires for EXTERNAL_NODE today. Production traffic uses
 * [MessengerFleetTransport] over [ai.droidcommand.fleet.messenger.PairedNodeGateway]. Android
 * has no system `ssh`; this class exists so a [ai.droidcommand.rootforge.RootForgeClient] can
 * still be constructed without a real transport. Do not read the failure string as a claim
 * that the hub Messenger path is unwired.
 */
class UnwiredMessengerTransport : RootForgeTransport {
    override fun exchange(requestLine: String, timeoutMs: Long): Result<String> =
        Result.failure(
            TransportException(
                "UnwiredMessengerTransport leftover: this is not MessengerFleetTransport " +
                    "(the live EXTERNAL_NODE path FleetModule wires)",
            ),
        )
}

/**
 * Leftover SSH-registry EXTERNAL_NODE adapter. Production Fleet Orchestrator EXTERNAL_NODE
 * traffic goes through [MessengerFleetTransport] (see `FleetModule.provideFleetNodeTransport`),
 * not this class.
 *
 * RootForge here still goes through `RootForgeNodeRegistry`/`RootForgeClient`. With no paired
 * SSH node the answer is "pairing required". Even with one, [UnwiredMessengerTransport] cannot
 * deliver a Messenger session — that is this leftover adapter, not a statement about
 * [MessengerFleetTransport]. Other external nodes (File Manager, Keyboard) are a package probe
 * only. Never sends a command. Hardware proof of any live Messenger session is pending.
 */
class ExternalNodeTransport(
    private val rootForge: RootForgeNodeRegistry,
    private val isInstalled: (String) -> Boolean,
) : FleetNodeTransport {
    override fun probe(node: FleetNode): FleetProbeResult {
        if (node.id == ROOTFORGE_ID) return probeRootForge()
        val pkg = node.packageName ?: return FleetProbeResult(FleetReachability.UNKNOWN, "Pairing required. No package to probe.")
        return if (isInstalled(pkg)) {
            FleetProbeResult(
                FleetReachability.UNKNOWN,
                "Pairing required. $pkg is installed, but the hub does not bind its Messenger bridge (planned or stub).",
            )
        } else {
            FleetProbeResult(FleetReachability.OFFLINE, "Not installed ($pkg)")
        }
    }

    private fun probeRootForge(): FleetProbeResult {
        val nodeId = rootForge.nodeIds.sorted().firstOrNull()
            ?: return FleetProbeResult(
                FleetReachability.UNKNOWN,
                "Pairing required. No RootForge node is paired. This leftover SSH-registry adapter is not MessengerFleetTransport.",
            )
        val client = rootForge.clientFor(nodeId)
            ?: return FleetProbeResult(FleetReachability.UNKNOWN, "Pairing required. No client for node $nodeId.")
        return when (val result = client.capabilities()) {
            is RootForgeResult.Success -> FleetProbeResult(FleetReachability.REACHABLE, "Bridge answered capabilities for node $nodeId (read-only)")
            is RootForgeResult.Failure -> when (result.kind) {
                FailureKind.TRANSPORT -> FleetProbeResult(FleetReachability.OFFLINE, "Bridge unreachable: ${result.message}")
                else -> FleetProbeResult(FleetReachability.UNKNOWN, "Bridge replied but is not usable (${result.kind}): ${result.message}")
            }
        }
    }

    override fun sendCommand(node: FleetNode, capabilityId: String, inputJson: String): ToolResult =
        ToolResult.Failure("${node.displayName} is read-only here: pairing required, no command transport")

    companion object {
        const val ROOTFORGE_ID = "rootforge"
    }
}

/** TERMUX_IPC. Reads the existing [TermuxExecutor]; commands are not routed through the fleet screen. */
class TermuxFleetTransport(
    private val executor: TermuxExecutor,
    private val isInstalled: (String) -> Boolean,
) : FleetNodeTransport {
    override fun probe(node: FleetNode): FleetProbeResult {
        val pkg = node.packageName ?: return FleetProbeResult(FleetReachability.UNKNOWN, "No package to probe")
        if (!isInstalled(pkg)) return FleetProbeResult(FleetReachability.OFFLINE, "Not installed ($pkg)")
        return if (executor.isAvailable()) {
            FleetProbeResult(FleetReachability.REACHABLE, "Installed, and the Termux executor reports available (no command channel here)")
        } else {
            FleetProbeResult(
                FleetReachability.UNKNOWN,
                "No runner yet. $pkg is installed, but the Termux executor does not report available, and this screen has no Termux command channel.",
            )
        }
    }

    override fun sendCommand(node: FleetNode, capabilityId: String, inputJson: String): ToolResult =
        ToolResult.Failure("${node.displayName}: no runner yet")
}
