package ai.droidcommand.cli

import ai.droidcommand.agent.AgentMode
import ai.droidcommand.agent.AgentState
import ai.droidcommand.agent.AgentStateMachine
import ai.droidcommand.agent.DroidCommandSession
import ai.droidcommand.agent.ToolExecutor
import ai.droidcommand.agent.ToolRegistry
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.describe
import ai.droidcommand.config.ConfigSource
import ai.droidcommand.config.EnvConfigSource
import ai.droidcommand.llm.factory.LlmProviderFactory
import ai.droidcommand.remote.HttpTransport
import ai.droidcommand.remote.JdkHttpTransport
import kotlin.system.exitProcess

/**
 * The device-free CLI entrypoint named in `docs/AUDIT_2026-09-05.md`'s
 * "wiring LlmProviderFactory into ObjectiveEngine/DroidCommandSession"
 * addendum as the only thing left between `core-llm-factory` and a
 * genuinely running Pilot/Forge session: a real [DroidCommandSession],
 * dispatching either a single Pilot instruction or a full Forge objective
 * driven by a [ai.droidcommand.agent.Planner] built straight from process
 * environment configuration via [LlmProviderFactory.createPlanner].
 *
 * Distinct from the PLANNED Android `:app` module (`docs/ARCHITECTURE.md`'s
 * "Android application module (UI shell, DI wiring)" row) — that one stays
 * gated on an Android SDK/device this environment does not have. This
 * module is the "or a future CLI" half of that same gap, and needs neither.
 *
 * The [Planner] is built lazily, only when `forge` actually runs: `pilot`
 * needs no LLM at all, so an unconfigured environment can still dispatch
 * Pilot instructions against [EchoTool] without tripping over a missing
 * `DROIDCOMMAND_LLM_PROVIDER_IDS`.
 */
fun main(args: Array<String>) {
    if (args.isEmpty() || args[0] in setOf("-h", "--help", "help")) {
        printUsage()
        exitProcess(if (args.isEmpty()) 1 else 0)
    }

    val session = buildSession()

    val exitCode = when (val command = args[0]) {
        "list-tools" -> {
            printTools(session.registry)
            0
        }
        "pilot" -> runPilot(session, args.drop(1))
        "forge" -> runForge(session, args.drop(1))
        else -> {
            System.err.println("Unknown command '$command'")
            printUsage()
            1
        }
    }
    exitProcess(exitCode)
}

/** Wraps the [ToolRegistry] alongside the session so callers (`main`, tests) can inspect it without a second constructor. */
class CliSession(val session: DroidCommandSession, val registry: ToolRegistry)

internal fun buildSession(): CliSession {
    val registry = ToolRegistry().apply { register(EchoTool()) }
    val stateMachine = AgentStateMachine()
    val executor = ToolExecutor(registry, stateMachine)
    return CliSession(DroidCommandSession(registry, executor, stateMachine), registry)
}

internal fun printUsage() {
    println(
        """
        DroidCommand AI — device-free CLI entrypoint

        Usage:
          list-tools                     List every registered tool and its spec
          pilot <tool> [key=value ...]   Run a single Pilot Mode tool invocation
          forge <objective text...>      Run a Forge Mode objective via an LLM planner

        Forge Mode reads its LLM configuration from the process environment
        (ai.droidcommand.config.EnvConfigSource), using the same
        DROIDCOMMAND_LLM_PROVIDER_IDS / DROIDCOMMAND_LLM_PROVIDER_<ID>_* keys
        core-config.MultiLlmConfigLoader defines — see its doc comment for the
        full list. At least one provider must be configured or 'forge' fails
        with a clear error instead of a stack trace.

        Examples:
          pilot echo text=hello
          forge "Echo the word hello"
        """.trimIndent(),
    )
}

internal fun printTools(registry: ToolRegistry) {
    val specs = registry.list()
    if (specs.isEmpty()) {
        println("No tools registered.")
        return
    }
    for (spec in specs) {
        println("${spec.name} (${spec.securityLevel}, modes=${spec.allowedModes}): ${spec.description}")
    }
}

internal fun runPilot(cliSession: CliSession, rest: List<String>): Int {
    if (rest.isEmpty()) {
        System.err.println("Usage: pilot <tool> [key=value ...]")
        return 1
    }
    val toolName = rest[0]
    val input = mutableMapOf<String, String>()
    for (pair in rest.drop(1)) {
        val separator = pair.indexOf('=')
        if (separator < 0) {
            System.err.println("Invalid input '$pair'; expected key=value")
            return 1
        }
        input[pair.substring(0, separator)] = pair.substring(separator + 1)
    }

    val result = try {
        cliSession.session.runPilotInstruction(toolName, input)
    } catch (e: Exception) {
        System.err.println("Error running tool '$toolName': ${e.message}")
        return 1
    }
    println(result.describe())
    return if (result is ToolResult.Failure) 1 else 0
}

/**
 * [configSource]/[transport] default to real, process-wide implementations
 * but are caller-injectable so tests can supply the same
 * `MapConfigSource`/scripted-`HttpTransport` fixtures
 * `LlmProviderFactoryPlannerIntegrationTest` already established, without
 * touching real environment variables or the network.
 */
internal fun runForge(
    cliSession: CliSession,
    rest: List<String>,
    configSource: ConfigSource = EnvConfigSource(),
    transport: HttpTransport = JdkHttpTransport(),
): Int {
    if (rest.isEmpty()) {
        System.err.println("Usage: forge <objective text...>")
        return 1
    }
    val objective = rest.joinToString(" ")

    val planner = try {
        LlmProviderFactory.createPlanner(configSource, transport)
    } catch (e: Exception) {
        System.err.println("Could not build an LLM planner from configuration: ${e.message}")
        System.err.println(
            "Set DROIDCOMMAND_LLM_PROVIDER_IDS and the matching DROIDCOMMAND_LLM_PROVIDER_<ID>_* keys " +
                "(see core-config.MultiLlmConfigLoader's doc comment for the full list).",
        )
        return 1
    }

    cliSession.session.switchMode(AgentMode.FORGE)
    val outcome = cliSession.session.runForgeObjective(objective, planner)
    println("Final state: ${outcome.finalState}")
    println("Iterations: ${outcome.iterations}")
    return if (outcome.finalState is AgentState.Completed) 0 else 1
}
