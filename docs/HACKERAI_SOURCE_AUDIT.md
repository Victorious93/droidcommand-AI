# HackerAI (hackeraiETC) Source Audit

**Purpose.** The project owner's original migration brief ("analyze,
rebuild, adapt, and integrate useful functionality from the provided
HackerGPT source into DroidCommand AI") refers to
`Victorious93/hackeraiETC` — the "HackerAI" Next.js/TypeScript/Convex web
app — as the source codebase, not a separate "HackerGPT" repository (none
exists; confirmed 2026-09-26, no reference to "HackerGPT" appears anywhere
in this repo's own docs before this entry). This document is that audit,
scoped to the AI agent/tool orchestration domain (`lib/api/agent-*.ts`,
`lib/ai/subagents/`), since that is what the brief's own phase list
(AI Provider Layer, Tool System, Command Execution, Agent Request
Lifecycle) is actually about, and because this repo's own architecture
already covers most of that domain independently.

**What this document is not:** an exhaustive line-by-line diff of both
codebases (hackeraiETC has ~100+ files under `lib/ai`/`lib/api`; this repo
has 20 Kotlin modules). It is a focused read of the highest-signal files
on both sides, cross-checked against this repo's actual current
implementation rather than assumed from module names. Treat unreviewed
areas of hackeraiETC (chat UI, billing, Convex schema, desktop/local
sandbox transports, most of `lib/ai/tools/`) as **not audited** — don't
cite their absence from this document as "reviewed and found irrelevant."

**Reconciliation with this repo's existing roadmap:** this repo already
has a Consumer Product Roadmap (Phase 0–5, see below in this file) and a
fully-implemented single-agent loop (`ObjectiveEngine`/`ToolExecutor`/
`SecureToolExecutor`/`ApprovalFlow`, CAP-001–CAP-014). The brief's generic
Phase 0–10 (Project Discovery, Dependency Audit, Core Architecture, AI
Provider Layer, Tool System, Command Execution, UI Integration, Security,
Testing, Final Integration) is **not** being restarted from scratch —
those phase numbers collide with the existing Consumer Roadmap's Phase
0–5 and most of what they'd produce already exists here in more
Android-appropriate form. Findings below are framed as gaps or
enhancements against what's *already built*, not a fresh migration plan.

No code was changed to produce this document. Per this repo's own
workflow rules, any of the "REIMPLEMENT" items below needs its own
Plan-Mode scoping and owner approval before implementation — this
document identifies candidates, it doesn't commit to building them.

---

## Summary

| hackeraiETC component | Domain | Classification | Where it would land |
|---|---|---|---|
| Multi-agent delegation (`create_agent`/`delegate_task`/`send_message_to_agent`/`wait_for_agents`/`list_agents`/`cancel_agent`) | `lib/ai/subagents/contracts.ts` | **REIMPLEMENT** (new capability, no Android equivalent exists) | New roadmap candidate — see below, not an existing phase |
| Runtime re-authorization per tool call (`runtime-authorization.ts`) | subagent execution guard | **REIMPLEMENT** (concept), only meaningful once multi-agent delegation exists | Same, paired with the above |
| Step-budget reservation for forced result finalization (`runtime-recovery.ts`) | agent loop termination | **REIMPLEMENT** (small, standalone) | `ObjectiveEngine` enhancement |
| LLM-provider error-category retry taxonomy (`runtime-recovery.ts`) | provider call resilience | **REIMPLEMENT** | Phase 1 (Multi-Provider Cloud BYOK) |
| Compact handle vs. durable ID (`agent-handle.ts`) | model-facing identifiers | **OPTIONAL** (trivial, only needed once agent IDs are model-exposed) | Same as multi-agent delegation |
| Structured-result schemas w/ evidence-verification gap enforcement (`contracts.ts` security_task/security_validation) | anti-hallucination for pentest findings | **OPTIONAL** — domain-specific to security-testing workloads | Only relevant if DroidCommand AI takes on a pentest/bug-bounty use case; the current Consumer Roadmap (privacy-first chat) doesn't call for it |
| Strix pentest skill catalog (`lib/ai/subagents/skills/`, vendored from `third_party/`) | domain skill library | **REMOVE** (not applicable) | hackeraiETC-specific; this repo's own `Persona`/Phase 3 "Skill" concept is a different, general-purpose thing and shouldn't be conflated with this |
| Agent HTTP route lifecycle (`agent-approval-route.ts`, `agent-cancel-route.ts`, `agent-resume-route.ts`, `agent-status-route.ts`, `agent-trigger-route.ts`) | web-server-specific request handling | **REPLACE** — the *state machine* these routes expose (received→planning→...→completed/failed/cancelled) already exists here as `AgentState`; the HTTP transport itself doesn't apply to a JVM/Android library | N/A — conceptually already covered by `AgentState`/`ObjectiveEngine` |
| Billing/rate-limit finalization (`billing.ts`, `rate-limit-finalization.ts`), region guard (`region-guard.ts`) | hosted-SaaS concerns | **REMOVE** (not applicable) | This repo ships a library/APK, not a metered multi-tenant service |

---

## Detail

### 1. Multi-agent delegation — genuinely new capability

hackeraiETC's `lib/ai/subagents/contracts.ts` defines a full parent/child
agent model: a parent run can `create_agent`/`delegate_task` to spin up a
child with its own profile, capability bundle, budget
(`SUBAGENT_MAX_COST_DOLLARS`, `SUBAGENT_ORCHESTRATION_BUDGET_DOLLARS`),
step limit (`SUBAGENT_MAX_STEPS = 50`), and wall-clock bounds
(`SUBAGENT_MAX_ACTIVE_SECONDS`, `SUBAGENT_RESULT_DEADLINE_SECONDS`,
`SUBAGENT_MAX_DURATION_SECONDS`), then `send_message_to_agent`/
`wait_for_agents`/`list_agents`/`cancel_agent` to coordinate.

This repo has nothing equivalent. `ObjectiveEngine` runs exactly one
agent loop per `DroidCommandSession`; `MacroExecutor`/`MacroScheduler`
run pre-defined multi-step macros, not LLM-directed child agents. If
DroidCommand AI ever wants "let the assistant delegate a sub-task to a
parallel agent" (e.g., "research this while I keep chatting"), this is
the closest real precedent to build from — but it is new work, not a
port: hackeraiETC's version is deeply coupled to Trigger.dev (durable
run IDs, `parent_trigger_run_id`) and Convex persistence, neither of
which exists here. The transferable part is the *shape* (profiles,
capability bundles, budgets, the six-verb coordination API), not the
implementation.

**Not currently in the Consumer Roadmap (Phase 0–5).** If the owner wants
this, it needs its own phase (a "Phase 6" candidate) scoped explicitly —
it is a materially different feature from anything Phase 0–5 already
describes, and per this repo's own roadmap workflow rules, no such phase
should be started without being written up and approved the same way
Phase 0–5 were.

### 2. Runtime re-authorization per tool call

`runtime-authorization.ts`'s `guardSubagentToolExecutions` wraps every
tool so it re-checks — at the moment of *execution*, not just at spawn
time — that the child agent's run hasn't been superseded and the parent
run is still active. This matters only in a system with concurrent,
independently-cancellable child agents, which this repo doesn't have yet.
It's a real hardening pattern worth carrying over **if and when** item 1
is built, not something to bolt onto the existing single-agent
`ObjectiveEngine` today (its `isCancelled()` check already covers the
single-agent case).

### 3. Step-budget reservation for forced finalization

`runtime-recovery.ts` reserves the last `1 + SUBAGENT_MAX_RESULT_RECOVERY_FAILURE_RETRIES`
steps of a subagent's budget specifically for emitting its structured
result, so an agent that's about to run out of steps doesn't just die
mid-exploration — it's forced to answer with what it has
(`getSubagentExplorationStepLimit`, `buildMissingSubagentResultRecoveryMessage`).

`ObjectiveEngine.run` has no equivalent: hitting `maxIterations` simply
transitions to `AgentState.Failed("Objective did not complete within N
iterations")` with whatever partial context exists, and the caller gets a
failure rather than a best-effort answer. This is a small, self-contained
improvement independent of multi-agent work — reserve the last iteration
(or two) to force a `PlannerDecision.Complete`-style wrap-up instead of a
hard failure. Concrete and low-risk to scope as a standalone follow-up to
CAP-001/CAP-002 (Context/Token Budget Manager), not gated on anything
else in this list.

### 4. LLM-provider error-category retry taxonomy

`runtime-recovery.ts` classifies provider failures into categories
(`rate_limited`, `provider_5xx`, `stream_terminated`, `timeout`,
`content_blocked`) and retries only the ones known to be transient, with
exponential backoff + jitter (`750 * 2^retries`) and a bounded retry
count. This is specifically about **LLM API call failures**, not tool
execution failures.

This repo's `RetryPolicy` (`core-agent/ToolExecutor.kt`) is a flat
`maxAttempts` + backoff function applied to *tool* execution, and
retries any `ToolResult.Failure` uniformly — it has no concept of *why*
a call failed, and it isn't used for LLM provider calls at all (no
`LlmProvider`/`ModelRouter` file showed provider-error-aware retry logic
in this audit). This repo's own `CLAUDE.md` already documents that
`AiProviderSelector` has no real latency/resource measurement yet — this
is a related, concrete gap in the same area: **when Phase 1 (Multi-Provider
Cloud BYOK) adds `core-llm-google`/`core-llm-groq` and real network calls
against multiple cloud APIs, provider-error-category-aware retry (as
opposed to today's blind flat retry) becomes directly relevant** and
hackeraiETC's category list is a reasonable, already-battle-tested
starting taxonomy to adapt (not copy verbatim — droidcommand-AI has no
`content_blocked` concept without abliteration-style provider concerns,
and no Convex/Trigger-specific error codes).

### 5. Structured-result schemas with evidence-verification gaps

hackeraiETC's `security_task`/`security_validation` subagent profiles
enforce, at the schema level, that a "confirmed" vulnerability verdict
must carry at least one reproduction step and one evidence reference, and
that any coverage claim without evidence must have a
server-recorded verification gap — a real anti-hallucination mechanism
for pentest findings specifically.

This has no current analog in DroidCommand AI and, per the existing
Consumer Roadmap, doesn't need one — that roadmap describes a
general-purpose privacy-first chat app (templates, skills, voice, RAG),
not a security-testing tool. **Flagging as optional rather than removing
outright** only because this repo already has `core-security`/`core-root`/
Magisk root-execution modules that suggest some overlap with
power-user/security-adjacent use cases — if the owner ever wants
DroidCommand AI to support verifiable security-finding workflows, this
schema-enforced evidence discipline is the right pattern to adapt. Absent
that explicit direction, it's out of scope.

### 6. Strix pentest skill catalog

`lib/ai/subagents/skills/` vendors a generated catalog from the Strix
project (`third_party/`, per `AGENTS.md`'s own note that these are "a
separate application dependency," not something to copy into a
downstream repo's own skills folder). This is domain content for
penetration-testing subagents specifically — not a general "skill" or
"persona" concept. Don't conflate it with this repo's own Phase 3
`Skill`/`Persona` design question (already flagged in the existing
roadmap as "is `Skill` genuinely new or the UI surface for the existing
`Persona` type?") — that's a different, general-purpose feature, and
Strix's catalog isn't relevant to it either way.

### 7. Agent HTTP routes and hosted-SaaS concerns

`agent-approval-route.ts`, `agent-cancel-route.ts`, `agent-resume-route.ts`,
`agent-status-route.ts`, `agent-trigger-route.ts`, `billing.ts`,
`rate-limit-finalization.ts`, `region-guard.ts` are Next.js API routes and
metered-SaaS bookkeeping (Convex/Trigger.dev/billing/data-residency).
None of it applies to a Kotlin library + Android app with no server
component of its own. The *state transitions* those routes expose
(received → planning → tool_selection → validating →
confirmation_required → executing → handling_result → completed/failed/
cancelled) are already covered here by `AgentState`
(`Planning`/`ExecutingTool`/`Observing`/`Recovering`/`Completed`/`Failed`/
`Cancelled`) — closely enough that no port is needed, just a note that
the two model the same lifecycle independently.

---

## Recommendation

Nothing in this audit is urgent or blocking. In priority order, if the
owner wants to act on it:

1. **Item 3 (step-budget reservation for forced finalization)** — smallest,
   self-contained, no new module, improves an existing implemented
   component (`ObjectiveEngine`). Best next candidate if any of this is
   picked up.
2. **Item 4 (provider error-category retry)** — naturally scoped into
   Phase 1 when real multi-provider network calls land; premature before
   then since there's little to retry against today.
3. **Item 1 + 2 (multi-agent delegation + re-authorization)** — real,
   valuable, but a genuinely new phase-sized feature, not a fast follow.
   Needs its own write-up (hypothesis, acceptance criteria, branch name)
   in the same style as Phase 0–5 before any implementation starts.
4. **Item 5 (evidence-gated security findings)** — hold until/unless the
   owner explicitly wants a pentest-workflow use case.
5. **Items 6–7** — no action; not applicable to this repo.
