# Activity view and multi-bot missions (design + status)

Written 2026-11-21 after the owner asked: *should there be a screen showing what DroidCommand AI is doing, the same
for each bot, and — when VictorBots is connected — a way to deploy several bots on several tasks across the fleet
apps, API and MCP; "something like Grok Bot has".* Labels: **[Certain]** read in code/docs of this workspace's repos,
**[Likely]** follows from what was read, **[Guessing]** assumption. Nothing below was run on a device.

## 1. Answer

Yes to both screens, and the missing piece is not UI: today nothing *records* what a bot is doing.

| Question the owner wants answered | Today | Evidence |
|---|---|---|
| What is it doing right now? | Not answerable | **[Certain]** The Logs screen reads `JsonFileAuditLog`; `LogsViewModel`'s own doc says plain allowed tool runs are not recorded. |
| Did that bot's run finish or fail? | Not answerable | **[Certain]** `BotLifecycle` records `BOT_RUN_STARTED` only; its doc says there is no finished/failed event. |
| Which fleet app/phone is busy with what? | Only until restart | **[Certain]** The Fleet Orchestrator screen says status "is remembered only until this app restarts". |
| Is anything waiting for me? | Only while the dialog is on screen | **[Certain]** Approval is a modal (`ComposeApprovalPrompt`); nothing aggregates pending approvals per bot. |

## 2. What was built (`core-activity`, JVM, 29 tests)

A bounded in-memory feed, **separate from `AuditLog` on purpose**. **[Certain]** `JsonFileAuditLog.record` returns
`false` once it holds 10,000 lines and re-counts the file on every call; it is append-only and fails closed. Putting a
per-step progress feed in it would, sooner or later, make the audit log refuse security events. The activity feed does
the opposite: it drops its *oldest* entries and a failure to record never changes what a tool does.

Pieces: `ActivityRun` (start/finish/fail/cancel, ends once), `ActivityRecordingToolRunner` (wraps any `ToolRunner`,
passes every argument and the result through untouched), `ActivityApprovalPrompt` (records "waiting for you" without
deciding anything), `ActivityScope` (a per-thread marker so the one shared approval dialog knows *which* bot is asking —
tested with two parallel threads), `ActivityRedactor`, and `ActivityBoard` (derives *Waiting for you / Working / No
update for a while / Failed / Done / Stopped* from events, rows needing the owner first).

Limits, stated plainly: the feed is lost when the process dies; `ActivityRedactor` masks secret-looking argument keys
and known token shapes but is **not** a guarantee for free text, which is why only short bounded excerpts are stored,
never full output; it records only what is wrapped, and nothing in `:app` wraps anything yet.

## 3. The screen (not built — waiting for the reference)

Pattern taken from the owner's Grok Bot reference. **[Guessing]** on every detail below: the only description available
here came from web-search summaries (docs.x.ai and forum.cursor.com were unreachable from this container), not from the
app. Those summaries describe bots as teammates that work in parallel and "only come back when your approval is
needed", a per-bot task list, a live view of the machine a bot is working on, and a phone that is the control point
rather than the place the work runs. Only the *interaction pattern* is reused; no code, text or assets.

**What the owner's APK (Grok Bot v1.18.0, package `ai.x.grok.bot`) adds [Certain, manifest and archive layout only].**
It is a React Native/Expo app: its screens are in a 21 MB JavaScript bundle (`assets/index.android.bundle`), not in
Android layout files, so the APK cannot show what the screens look like. It was unzipped and statically inspected only
(never installed or run), and the bundle was not disassembled. The manifest shows push delivery through Firebase
Cloud Messaging (Braze, Expo notifications and a `co.anysphere.sandpush` service), a `VoiceCallForegroundService`, a
location task service and WorkManager's foreground service; it declares **no** foreground service for running bots.
That matches the "work runs elsewhere, the phone is the control point" description. **[Likely]** it also means Grok Bot's
"come back when I need approval" reaches the phone as a server push. **A self-hosted DroidCommand has no such server**,
so the equivalent for fleet work would be the phone's worker long-polling the VictorLinux coordinator from a foreground
service, or a push relay the owner chooses to run. Neither exists, and picking one is a decision (§5, item 3).
**Still needed: screenshots or a screen recording of the screens to match** (overview, a bot's detail/tasks, an approval
request, a group chat with several bots).

Planned shape, to be corrected against the owner's screenshots:

1. **Overview** — one row per bot from `ActivityBoard.overview`: name, status chip, current step, time since the last
   update. "Waiting for you" rows first, with the question shown and Allow / Decline reachable without leaving the list.
2. **Bot detail** — newest-first timeline of one run from `ActivityBoard.timeline` (each tool call with redacted
   arguments, result excerpt, approvals), plus Stop.
3. **Entry points** — a Home card ("2 working, 1 needs you") and an ongoing notification only once background execution
   exists (see §5).

The "live computer view" has no phone equivalent: a phone bot's honest live view is its **tool-step timeline**. For work
run on a VictorLinux PC the equivalent is the runner/coordinator log tail. **[Certain]** VictorLinux's phone mirroring
goes the other way (PC shows a USB-attached phone) and is unrelated.

## 4. Multi-bot deployment: who owns what

Do **not** build a second orchestrator inside DroidCommand. **[Certain]** all three repos already split the job, and the
splits are explicit:

| Concern | Owner | Source |
|---|---|---|
| Bot profiles, team chats, planning, proposing jobs | VictorBots | VictorBots `ARCHITECTURE.md`: bot-originated jobs are `PROPOSED`; only the owner approves and runs them |
| Queue, schedule, leases, retries, which phone takes which job | VictorLinux `victorfleet` coordinator | `fleet/docs/FLEET.md`: "VictorLinux coordinator (only)"; DAG plans, capability-aware assignment, plans reviewed before first dispatch |
| Whether *this phone* may run an operation, approvals, grants, audit | DroidCommand | same table: "DroidCommand on that phone (only)" |

So "VictorBots deploys several bots across the fleet" means: VictorBots (or the owner) produces a reviewed **plan**, the
coordinator schedules it, DroidCommand's worker mode executes each job through `SecureToolExecutor`, and the activity
feed shows every step on each phone. **[Certain]** what is real today: the coordinator and protocol are tested with
simulated workers and the Kotlin `core-fleet-worker`; the worker implements only `hash_files`; no Android pieces
(Keystore identity, foreground service, pairing screen) exist; nothing ran on a phone.

## 5. Decisions only the owner can make (none taken)

1. **Batch approval.** **[Certain]** `COMPANION_EXEC` v1 has no standing grants: one request, one Allow tap, and
   VictorBots' roadmap says "no auto-approve, no scheduling of DroidCommand jobs". N bots × M steps is N×M taps. The
   workable alternative is one approval of a *reviewed plan* that issues time-boxed, scope-limited grants
   (`GrantStore` and the `GRANT_*` audit events already exist). That relaxes a rule written on purpose; it needs the
   owner's explicit yes, a threat model, and tests before any code.
2. **Attribution across repos.** To show "this fleet job was requested by bot X" the coordinator's job payload needs a
   field. **[Certain]** the protocol is versioned and guarded by byte-identical golden fixtures in two repos, so this is
   a coordinated change, not a quick edit.
3. **Background execution.** Bots that keep working with the app closed need an Android foreground service (or work
   running on the PC). **[Certain]** neither exists for fleet work yet (`FLEET_WORKER.md`, "Not implemented").
4. **Model-planned missions.** **[Certain]** the coordinator states "Model-assisted planning is not implemented";
   submission is manual and validated. A bot that decomposes a goal into a plan would be new, and its output must stay
   a plan the owner reviews, never a self-starting job (bot text is untrusted by design in all three repos).
5. **MCP.** Bots should reach MCP only through each bot's existing per-bot tool allow-list (`ScopedToolRunner`,
   `McpBridgedTool`), not by being handed a broad MCP connection.
6. **Parallelism limit.** **[Certain]** `ForegroundGate` allows one UI-driving job per phone at a time; real parallelism
   comes from several phones, the PC, or non-UI operations.

## 6. Suggested order

1. (done) `core-activity` foundation.
2. `:app` wiring — wrap the bot runner and approval prompt, add `BotLifecycle` finish/fail — and the Overview/Detail
   screens, laid out from the owner's reference. Needs an Android SDK or CI to compile.
3. Fleet-job events from `core-fleet-worker` into the same feed.
4. Decisions 1–2, then plan submission from VictorBots to the coordinator.
