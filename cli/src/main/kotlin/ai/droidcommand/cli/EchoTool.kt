package ai.droidcommand.cli

import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolSpec

/**
 * The only tool this entrypoint registers by default. Deliberately NORMAL
 * security level and side-effect-free: every real tool this repository
 * already ships that does anything sensitive (`core-shell.ShellTool`,
 * `core-root.RootTool`, `core-build.BuildTool`, ...) is designed to run
 * behind `core-security.SecureToolExecutor`'s policy/approval/audit gate,
 * not the plain `ToolExecutor` [ai.droidcommand.agent.DroidCommandSession]
 * takes. Wiring one of those straight into this CLI's plain executor would
 * silently bypass that gate for both Pilot instructions and, worse,
 * Forge-mode objectives an LLM planner decides on its own — so this first
 * slice registers only this harmless tool and leaves real capability
 * wiring to the `SecureToolExecutor` integration named as follow-up work
 * in `docs/AUDIT_2026-09-05.md`.
 */
class EchoTool : Tool {
    override val spec = ToolSpec(
        name = "echo",
        description = "Echoes back its 'text' input. Harmless smoke-test tool with no side effects.",
    )

    override fun execute(input: Map<String, String>): ToolResult = ToolResult.Success(input["text"] ?: "")
}
