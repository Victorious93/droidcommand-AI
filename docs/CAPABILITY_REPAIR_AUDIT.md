# Capability repair audit — Kai integration, corrected intent

This document is the project owner's requested audit/repair pass over the
work performed under the original "Integrate Kai capabilities into
DroidCommand AI" prompt, per the follow-up clarification: **architectural
independence with selective, licensed reuse** — DCA offers equivalent
capabilities through its own architecture/identity/settings/permission
system, not by merging or depending on Kai.

## 0. What this audit actually found: there is no separate "previous run" to recover

The follow-up prompt assumes a prior Claude Code session's work needs to be
located and attributed from git history, session records, or a previous
commit. That assumption does not hold here: **this audit is being written
in the same session, same turn, immediately after the original prompt's
work, before anything was committed.** `git status` at the start of this
audit showed:

```
 M core-mcp/build.gradle.kts
 M core-mcp/src/main/kotlin/ai/droidcommand/mcp/McpToolServer.kt
 M core-mcp/src/test/kotlin/ai/droidcommand/mcp/McpToolServerTest.kt
 M docs/ARCHITECTURE.md
?? docs/KAI_INTEGRATION.md
?? docs/KAI_INTEGRATION_PLAN.md
```

No commit, no push, no PR exists yet for the original prompt's work — so
there is no commit range to diff, no prior session record to read, and no
attribution uncertainty to resolve. The "earlier run" and "this audit" are
the same working tree at two points a few minutes apart. This is stated
plainly rather than manufacturing a recovery narrative the evidence doesn't
support, per the owner's own "do not invent attribution when the baseline
is uncertain" instruction — here the baseline is not uncertain, it is
simply not yet a separate commit.

Consequence: Section 1 ("recover what was actually done") is answered
directly from the uncommitted working tree below, not from git log/diff
archaeology.

## 1. What the original prompt's work actually changed

Four files modified, two created, all still uncommitted at audit time:

1. **`core-mcp/src/main/kotlin/ai/droidcommand/mcp/McpToolServer.kt`** —
   `McpToolServer`'s `executor` constructor parameter widened from the
   concrete `core-agent.ToolExecutor` to the `core-agent.ToolRunner`
   interface (a pure widening — `ToolExecutor` already implements
   `ToolRunner`, so every existing caller compiles and behaves unchanged).
   This lets a caller pass `core-security.SecureToolExecutor` instead,
   gaining its full policy/approval/grant/audit pipeline for MCP-originated
   calls. Separately, the argument-mapping lambda in `buildServer()` no
   longer drops a JSON array/object tool-call argument; it re-serializes
   it to compact JSON text and passes it through, so the value reaches the
   tool instead of being silently discarded. Doc comments updated to match.
2. **`core-mcp/src/test/kotlin/ai/droidcommand/mcp/McpToolServerTest.kt`** —
   the pre-existing test that asserted the drop behavior was split into two
   (one unchanged string/number case, one new nested-array-and-object
   preservation case), and a new test drives a real `SecureToolExecutor`
   (not a fake) through `McpToolServer` end to end over the real stdio
   transport, proving the `ToolRunner` widening actually works, not merely
   that it compiles.
3. **`core-mcp/build.gradle.kts`** — added `testImplementation(project(":core-security"))`.
   Main source gained **no** new dependency; only the test source needed
   `core-security` to build the `SecureToolExecutor` integration test.
4. **`docs/ARCHITECTURE.md`** — the existing MCP-integration status row
   updated with a dated note describing the two changes above.
5. **`docs/KAI_INTEGRATION.md`** (new) — a 14-row, evidence-based
   capability map (`KAI-001` through `KAI-014`), each row read against
   Kai's own `docs/features/*.md` and cross-checked against the specific
   DCA source files its Decision depends on.
6. **`docs/KAI_INTEGRATION_PLAN.md`** (new) — a 6-phase implementation
   plan mapping the capability map onto dependency order, explicitly
   marking everything beyond the one `core-mcp` change as NOT STARTED.

**Everything else in the repository is unchanged.** No new module was
added to `settings.gradle.kts`. No code was copied from the Kai checkout
into DCA's source tree. No `:app`/`:core-companion` inclusion was
attempted. No provider, memory, scheduler, MCP-client, skill, Linux-
sandbox, personal-tool, or data-portability *code* was written — those
remain documentation-only (capability map + plan), correctly marked
NOT STARTED rather than claimed done.

**Attribution:** all of the above is self-attributed to this same session
with full confidence — there is no uncertainty to flag, per Section 0.

## 2. Decision-framework audit: does anything violate architectural independence?

Walking the follow-up prompt's own decision table against what exists:

| Check | Finding |
|---|---|
| Does DCA's build or runtime require a Kai checkout, submodule, or running Kai service? | **No.** `core-mcp`'s only new dependency is `core-security` (an existing DCA module), test-scope only. No `build.gradle.kts` anywhere in the repo references Kai. Verified by grep across the working tree (see §5). |
| Was any Kai source file copied into DCA? | **No.** The one code change (`McpToolServer`'s argument mapping) is original logic (generic JSON re-serialization), not derived from or resembling any specific Kai source file — it was written to fix a gap this session identified in DCA's own code, not ported from Kai. |
| Does the capability map claim or imply a formal clean-room process for *future* adaptation work? | **Yes, originally — now corrected.** `docs/KAI_INTEGRATION.md` used the word "clean-room" nine times to describe planned future adaptation of Kai's *documented behavior* (e.g., the SSRF-guard shape for `fetch_url`, the cron-trigger taxonomy, the kai-ui node schema). Clean-room specifically requires the implementer never to have seen the reference implementation; that precondition does not hold — this session read Kai's docs and source directly. **Repaired in this audit:** every occurrence was rewritten to "non-verbatim rewrite" / "adapted after reading Kai's source/docs directly," and the Licensing section now states explicitly that attribution-based adaptation under Apache-2.0, not clean-room, is what's being claimed, with an instruction for future sessions to record which Kai file(s) informed each future implementation at the point it's actually written. This is the one concrete defect this audit found and fixed. |
| Does any decision introduce a second router/memory/secrets/policy stack? | **No — nothing beyond documentation exists yet to introduce one.** The capability map's own KAI-002 and KAI-004 rows explicitly call out DCA's existing `KnowledgeStore`/`AiProviderSelector` as the destination to *extend*, not duplicate, and name the specific repo precedent (`ModelRouter`/`AiProviderSelector`'s own "avoid a second competing concept" doc comments) as the reason. This is a stated design intent for not-yet-written code, not something to verify against an implementation that doesn't exist yet. |
| Was `:app`'s exclusion assumed rather than verified? | **Verified directly, not assumed.** This session ran `cat settings.gradle.kts` itself at the start of the original prompt's work and read the exclusion comment in full; it was not carried over from `CLAUDE.md` prose uncritically. Re-verified again for this audit — unchanged: `:app` and `:core-companion` are still absent from `include(...)` and the explanatory comment still cites "no Android SDK." |
| Is the MCP client/server distinction preserved? | **Yes.** `docs/KAI_INTEGRATION.md`'s KAI-006 row states directly that `core-mcp.McpToolServer` (DCA's tools exposed *to* an external client) and Kai's `McpClient`/`McpServerManager` (external tools pulled *into* Kai) are complementary, not competing, and plans a **new, separate** `core-mcp-client` module rather than overloading `core-mcp`. |
| Does the one real code change weaken any security boundary? | **No — it strengthens one.** Widening `McpToolServer` to accept `ToolRunner` is strictly additive capability (a caller *may* now pass `SecureToolExecutor`; nothing is forced to). The argument-preservation change only stops *silently discarding* data; it does not bypass any check — `ToolSpec`'s mode/initiator/permission/grant checks all still run exactly as before on whatever `ToolRunner` the caller supplies. |

## 3. Build/Android verification (re-run for this audit, not assumed from the original pass)

- `settings.gradle.kts` re-read directly: 24 modules included, `:app`/
  `:core-companion` excluded with an unchanged "no Android SDK" rationale.
  This environment still has no `ANDROID_HOME`/`ANDROID_SDK_ROOT`/`adb` —
  confirmed again, not carried over from a stale assumption.
- No Gradle project anywhere in the repo declares a dependency on a
  sibling Kai checkout, a Kai Maven coordinate, or a Kai Git submodule —
  confirmed by `grep -ri "kai" -- '*.gradle.kts' 'settings.gradle.kts'`
  across the repository returning no matches outside this session's own
  new documentation files.
- `./gradlew :core-mcp:test` — BUILD SUCCESSFUL, 10 tests (previously 8),
  0 failures, 0 errors.
- `./gradlew test --continue` (full 24-module suite) — BUILD SUCCESSFUL,
  0 failures, 0 errors across every module, 12 pre-existing skips
  (real-device-only tests with no `adb` device attached — unchanged from
  the 2026-10-06 baseline in `docs/AUDIT_2026-09-05.md`).
- **Not run, and not claimed:** `./gradlew :app:assembleDebug` (no Android
  SDK in this environment — unchanged limitation, stated honestly rather
  than attempted and faked). A JVM test pass is not evidence an Android
  build works; this audit does not conflate the two.

## 4. Capability status table (per the follow-up prompt's required format)

| Capability | Status | Evidence |
|---|---|---|
| Rich chat | PLANNED (documented only) | `docs/KAI_INTEGRATION.md` KAI-001 |
| Persistent memory and learning | PLANNED (documented only) | KAI-002 |
| Personality and system instructions | NO CODE GAP — DCA's `Persona`/`StyleProfile` already exceeds Kai's "soul" string; only a Settings UI is PLANNED | KAI-003 |
| Providers and fallback | PLANNED (documented only) beyond DCA's existing `AiProviderSelector`/`ModelRouter`/`LlmProviderFactory`, which are unchanged and already IMPLEMENTED | KAI-004 |
| On-device inference | PLANNED (documented only); DCA's Consumer-Roadmap GGUF commitment is explicitly preserved, not displaced | KAI-005 |
| MCP client | PLANNED (documented only) for the client module; the one Foundation-level prerequisite on the existing MCP **server** (`core-mcp`) is IMPLEMENTED and tested this session | KAI-006 |
| Skills | PLANNED (documented only) | KAI-007 |
| Tasks and heartbeat | PLANNED (documented only) beyond DCA's existing `MacroScheduler`/`MacroStore`, which are unchanged | KAI-008 |
| Linux environments and terminal | PLANNED (documented only); DCA's existing `core-termux` path is unchanged and explicitly preserved as the preferred first option | KAI-009 |
| Developer coding workspace | PLANNED (documented only) | KAI-010 |
| Interactive generated UI | PLANNED (documented only) | KAI-011 |
| Personal tools | PLANNED (documented only) beyond DCA's existing `core-tools-android` device tools, which are unchanged | KAI-012 |
| Data portability | PLANNED (documented only) | KAI-013 |
| Specialized integrations (Splinterlands) | OPTIONAL, explicitly out of core scope, lowest priority, not discarded | KAI-014 |

No capability above is claimed IMPLEMENTED unless it already was before
this prompt (persona, provider routing, scheduling, device tools, MCP
server) — the one net-new IMPLEMENTED item is the `core-mcp` Foundation
fix in §1, which is narrow by design and does not itself constitute any
of the 14 capability rows (it is a *prerequisite* for KAI-006's future
MCP-client work, named as such in the plan document).

## 5. Independence verification performed

```
grep -ril "kai" --include='*.gradle.kts' .          # no matches (this repo)
grep -n "kai" settings.gradle.kts                    # no matches
```

(commands described, not pasted as raw shell output, since both returned
nothing to show). This confirms the build graph has zero textual reference
to Kai anywhere outside this session's own new Markdown documentation.
A from-scratch clean checkout/build was not performed separately in this
audit (the existing working tree's `./gradlew test` run in §3 already
proves the declared build graph resolves and compiles with no Kai
involvement) — stated as the narrower check actually performed, per the
follow-up prompt's own "otherwise state the narrower check performed"
allowance.

## 6. Conclusion

The audit found **one concrete defect** in the original prompt's work: the
"clean-room" terminology in `docs/KAI_INTEGRATION.md`, which mischaracterized
planned future adaptation-after-reading-the-reference as a stronger
provenance guarantee than it actually is. This has been repaired in place
(§2, and directly in `docs/KAI_INTEGRATION.md`'s own text) rather than by
creating a parallel, contradictory account — the capability map and plan
documents are the amended record, and this file cross-references them
instead of duplicating their content.

No other material problem was found: no Kai build/runtime coupling exists,
no duplicate subsystem was introduced (nothing beyond documentation and
one narrow, additive `core-mcp` fix has been implemented yet), `:app`'s
exclusion was independently re-verified rather than assumed, and the one
real code change strengthens rather than weakens the existing security
boundary. Per the follow-up prompt's own "if the audit finds no material
problem, say so... do not manufacture code changes to justify this
follow-up" instruction, no further code changes were made beyond the
terminology repair above.

## 7. Governance gate, unchanged

Per `CLAUDE.md`'s Consumer Product Roadmap workflow rules (the same
phase-approval/no-automatic-PR gate `docs/KAI_INTEGRATION_PLAN.md` already
carries forward): Phase 0 is partially complete (capability map, baseline,
and the one Foundation-level `core-mcp` fix, all done and verified this
session); Phase 1 onward requires the project owner's explicit go-ahead
before implementation starts. This audit does not remove that gate and
does not proceed past it.
