# DroidCommand AI

These instructions apply to Codex and other coding agents working in this
repository. `CLAUDE.md` is the single source of truth for current state,
architecture, and the Consumer Product Roadmap — read it first and do not
duplicate its content here. `docs/SECURITY.md` is the single source of
truth for security requirements (storage, networking, secrets, logging,
tool execution, confirmation UX) — read it before implementing any tool
that touches the filesystem, network, or a subprocess.

Before implementing anything a migration brief describes as new (an
"AIProvider", "AgentController", "ToolRegistry", secure command
execution, etc.), check whether it already exists under a different name:
this repo already has `core-llm.LlmProvider`/`ModelRouter`/
`AiProviderSelector` (AI communication, decoupled from execution),
`core-agent.ObjectiveEngine`/`DroidCommandSession` (conversation state,
tool routing, cancellation/errors), and `core-agent.ToolRegistry`/
`core-security.SecureToolExecutor` (registry-only execution with
policy/approval/grant/audit gating). Extend these; don't build a second,
competing implementation of something this repo already has.
