package ai.droidcommand.cli

import ai.droidcommand.agent.AgentMode
import ai.droidcommand.agent.AgentState
import ai.droidcommand.agent.AgentStateMachine
import ai.droidcommand.agent.DroidCommandSession
import ai.droidcommand.agent.PermissionCategory
import ai.droidcommand.agent.ToolExecutor
import ai.droidcommand.agent.ToolRegistry
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.describe
import ai.droidcommand.build.BuildEnvironmentDetector
import ai.droidcommand.build.BuildPipeline
import ai.droidcommand.build.BuildRequest
import ai.droidcommand.build.BuildSecurityPolicy
import ai.droidcommand.build.BuildTarget
import ai.droidcommand.build.BuildTool
import ai.droidcommand.build.EnvironmentTool
import ai.droidcommand.build.ProjectType
import ai.droidcommand.build.SourceLocation
import ai.droidcommand.build.ToolAvailability
import ai.droidcommand.build.ToolCheckResult
import ai.droidcommand.build.WorkspaceManager
import ai.droidcommand.build.local.LocalProcessBuildExecutor
import ai.droidcommand.config.ConfigSource
import ai.droidcommand.config.EnvConfigSource
import ai.droidcommand.llm.factory.LlmProviderFactory
import ai.droidcommand.remote.HttpTransport
import ai.droidcommand.remote.JdkHttpTransport
import ai.droidcommand.root.NullRootExecutor
import ai.droidcommand.root.RootTool
import ai.droidcommand.security.ApprovalPrompt
import ai.droidcommand.security.SecureToolExecutor
import ai.droidcommand.security.SecurityPolicy
import ai.droidcommand.security.SecurityPolicyEnforcer
import ai.droidcommand.shell.ProcessBuilderShellExecutor
import ai.droidcommand.shell.ShellSecurityPolicy
import ai.droidcommand.shell.ShellTool
import ai.droidcommand.termux.NullTermuxExecutor
import ai.droidcommand.termux.TermuxTool
import java.nio.file.Path
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

/**
 * [approvalPrompt] defaults to the real, interactive [ConsoleApprovalPrompt] but is
 * caller-injectable, mirroring [runForge]'s `configSource`/`transport` parameters, so tests can
 * supply a scripted prompt instead of blocking on real stdin.
 *
 * `run_shell_command`/`run_root_command`/`run_termux_command`/`build_project` are now registered
 * alongside [EchoTool] — each is `SENSITIVE`/`ROOT`, so unlike `EchoTool` (`NORMAL`, auto-approved),
 * all four go through [SecureToolExecutor]'s real policy/approval gate before ever reaching their
 * executor. Every one is denied-by-default until explicitly configured or approved:
 * `run_shell_command` has an empty allowed-executable list unless
 * `DROIDCOMMAND_CLI_SHELL_ALLOWED_EXECUTABLES` is set; `run_root_command` is denied outright
 * ([SecurityPolicy.rootEnabled] is `false` and [PermissionCategory.ROOT] is not granted) regardless
 * of the approval prompt's answer, since this environment has no real root executor
 * ([NullRootExecutor] fails cleanly either way); `run_termux_command` is backed by
 * [NullTermuxExecutor] for the same reason — this environment has no adb/device/Termux, so it fails
 * cleanly rather than fabricating a Termux backend (a real one, `ai.droidcommand.termux.AdbTermuxExecutor`,
 * exists but is `IMPLEMENTED — NOT RUNTIME VERIFIED`; wiring it in here would need real hardware to
 * configure against, which this CLI's device-free design deliberately does not assume);
 * `build_project` uses [LocalProcessBuildExecutor], the same real, allow-listed
 * [ProcessBuilderShellExecutor] [ShellTool] uses — a build command is, at the OS level, just another
 * shell command, so both tools share one allowlist rather than this CLI inventing a second, separate
 * one; with no build command supplied at all it fails via [LocalProcessBuildExecutor]'s own "No
 * build command configured" message. No [ai.droidcommand.security.GrantStore]/[ai.droidcommand.security.AuditLog] is
 * wired: neither tool declares a `grantCapability`, and this CLI is one command per process
 * invocation with no persistence across runs, so an in-memory audit log nobody ever reads back
 * would be inert plumbing — a named follow-up, not silently added.
 */
internal fun buildSession(approvalPrompt: ApprovalPrompt = ConsoleApprovalPrompt): CliSession {
    val registry = ToolRegistry().apply {
        register(EchoTool())
        register(ShellTool(ProcessBuilderShellExecutor(shellSecurityPolicy())))
        register(RootTool(NullRootExecutor()))
        register(TermuxTool(NullTermuxExecutor()))
        register(BuildTool(buildPipeline(), ::buildRequestFromInput))
    }
    val stateMachine = AgentStateMachine()
    val delegate = ToolExecutor(registry, stateMachine)
    val policy = SecurityPolicy(grantedCategories = setOf(PermissionCategory.TERMINAL))
    val secure = SecureToolExecutor(registry, delegate, stateMachine, SecurityPolicyEnforcer(policy), approvalPrompt)
    return CliSession(DroidCommandSession(registry, secure, stateMachine), registry)
}

/**
 * Fail-closed by default (empty [ShellSecurityPolicy.allowedExecutables]), matching that type's own
 * documented default — an operator opts specific executables in via
 * `DROIDCOMMAND_CLI_SHELL_ALLOWED_EXECUTABLES` (comma-separated) rather than this CLI inventing an
 * arbitrary "safe commands" allowlist on their behalf. [ShellSecurityPolicy.allowedWorkingDirectories]
 * is fixed to the process's own working directory, matching [buildPipeline]'s identical choice.
 */
private fun shellSecurityPolicy() = ShellSecurityPolicy(
    allowedExecutables = System.getenv("DROIDCOMMAND_CLI_SHELL_ALLOWED_EXECUTABLES")
        ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }?.toSet() ?: emptySet(),
    allowedWorkingDirectories = listOf(System.getProperty("user.dir")),
)

/**
 * Workspace root defaults to the process's current working directory, matching this module's own
 * "no CLI-specific config file, use what the environment already gives you" precedent
 * ([EnvConfigSource]). [LocalProcessBuildExecutor] delegates the actual command spawn to the same
 * allow-listed [ShellSecurityPolicy]/[ProcessBuilderShellExecutor] [ShellTool] uses via
 * [shellSecurityPolicy] — no second, parallel process-execution path.
 */
private fun buildPipeline() = BuildPipeline(
    WorkspaceManager(listOf(Path.of(System.getProperty("user.dir")))),
    LocalProcessBuildExecutor(ProcessBuilderShellExecutor(shellSecurityPolicy())),
    object : BuildEnvironmentDetector {
        override fun check(tool: EnvironmentTool) = ToolCheckResult(tool, ToolAvailability.AVAILABLE)
    },
)

/**
 * [BuildRequest.securityConstraints.allowedWorkspaceRoots][BuildSecurityPolicy.allowedWorkspaceRoots]
 * must match [buildPipeline]'s [WorkspaceManager.authorizedRoots] exactly (as strings), or every
 * request is denied with `SecurityDenied` regardless of approval. [LocalProcessBuildExecutor] never
 * invents a build command from [ProjectType] — it reads `metadata["command.executable"]`
 * (required)/`metadata["command.args"]`/`metadata["artifact.paths"]` verbatim, so those three tool
 * input keys are threaded straight through to [BuildRequest.metadata] unchanged rather than this CLI
 * inventing a second naming scheme for the same thing.
 *
 * `sourceDir` is a **required** input, not defaulted to [buildPipeline]'s own working directory —
 * found the hard way (a real, approved `build_project` invocation) that a build's source directory
 * defaulting to the same directory a workspace gets created *under* makes `WorkspaceManager.create`'s
 * fresh workspace subdirectory itself part of the tree `WorkspaceManager.importSource` then tries to
 * copy, recursing until the filesystem refuses ("File name too long"). Requiring an explicit,
 * distinct `sourceDir` avoids ever constructing that self-referential request in the first place;
 * `WorkspaceManager`/`BuildPipeline` themselves behave exactly as documented (copy source into a new
 * workspace) — the hazard is only in *this CLI* ever defaulting the two to the same path, so the fix
 * belongs here, not in `core-build`.
 */
private fun buildRequestFromInput(input: Map<String, String>): BuildRequest {
    val sourceDir = input["sourceDir"] ?: throw IllegalArgumentException("Missing required input 'sourceDir'")
    val root = System.getProperty("user.dir")
    return BuildRequest(
        sourceLocation = SourceLocation.LocalDirectory(sourceDir),
        projectType = input["projectType"]?.let { ProjectType.valueOf(it.uppercase()) } ?: ProjectType.JVM,
        target = input["target"]?.let { BuildTarget.valueOf(it.uppercase()) } ?: BuildTarget.DEBUG,
        securityConstraints = BuildSecurityPolicy(allowedWorkspaceRoots = listOf(root)),
        metadata = input.filterKeys { it == "command.executable" || it == "command.args" || it == "artifact.paths" },
    )
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
