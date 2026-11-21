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

## 3. The screen (`:app`, built and compiled, never run)

**What Grok Bot's own docs say [Certain: `docs.x.ai/grok-bot/mobile.md` and `/overview.md`, read 2026-11-21].** The
phone is for starting work, answering questions, approving steps and reviewing results; "work continues in the cloud
when the app is closed". Home is a list of Bots and group chats. Each Bot has a switch, "Get notified when this Bot
finishes or is waiting for you". An approval is a card with **Allow once** and **Deny**, answered on the phone the card
names. "Review the computer" lets you watch the shared cloud computer and take over for a password or CAPTCHA. "Manage
your devices" lists each device as This device / Online now / last opened, with a per-computer execution setting of
Ask every time / Always allow / Never allow. A Bot can use the phone's own apps only while Grok Bot is open on it.
Routines have a schedule and run history, and Bots hand work to each other before bringing the owner in.

**What the owner's APK (v1.18.0, `ai.x.grok.bot`) adds [Certain, manifest and archive layout only].** It is a React
Native/Expo app, so its screens live in a 21 MB JavaScript bundle and the APK cannot show what they look like. It was
unzipped and statically inspected only (never installed or run) and the bundle was not disassembled. The manifest
shows push through Firebase Cloud Messaging and **no** foreground service for running bots, which fits "work runs
elsewhere, the phone is the control point".

**How that maps onto the fleet, and what was deliberately not copied:**

| Grok Bot | DroidCommand |
|---|---|
| Home list of Bots with status | **Activity** (`fleet/activity`, a tile on the Fleet home screen). Sections: *Needs you*, *Helper bots*, *Fleet apps that asked this phone* (VictorBots and the other paired apps, via COMPANION_EXEC), *Commands sent to fleet apps* (the Fleet Orchestrator). |
| "Waiting for you" | A status pill plus the question being asked. **v1 cannot answer from the list**: the answer is still the existing approval dialog or the fleet app's own Allow screen. |
| Open a Bot to see its work | Tap a row: its steps, newest first, with redacted arguments and a short result excerpt. |
| Live "computer" view | **Not built.** A phone bot has no cloud computer; the honest equivalent is the step list. For PC work it would be the VictorLinux runner/coordinator log, which is not wired. |
| Notify when finished / waiting | **Waiting only**: a notification "<bot> is waiting for you" while the app is in the background (`ActivityNotifier`); nothing on finish; nothing when the app is closed or killed (see §5, item 3). |
| Allow once / Deny, Ask every time / Never | Already exist (`ApprovalPrompt`, `FleetControlPolicy`) and are unchanged. |
| Routines and run history | Not built; VictorBots owns routines. |

Only the interaction pattern was followed: no code, text or assets were taken from the app.

**Where activity is recorded (all of it goes through the existing approval and audit path unchanged):**
DroidCommand's own Pilot instructions and Plan runs (`PilotController`, `PlanController`; the objective's text is not
recorded), helper-bot turns and their tool calls (`ChatViewModel`), requests from paired fleet apps (`CompanionExecActivity`:
"VictorBots asked: device.apps.list", waiting for you, allowed or declined, done), action commands sent to a fleet app
(`FleetOrchestratorViewModel`; read-only status checks are not recorded, because they run for every node each time the
screen opens), and every approval raised inside any of those (`ActivityApprovalPrompt`, which wraps the app's one
approval prompt).

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

## 6. Status and what is next

1. Done: `core-activity` (33 JVM tests).
2. Done, compiled and unit-tested, **never run on a device**: the recording hooks and the Activity screen in `:app`
   (`:app:compileDebugKotlin`, `:app:hiltJavaCompileDebug`, `:app:ktlintCheck`, 7 new app tests, the whole app unit suite).
3. Done after that, same caveats: Pilot and Plan recorded under "DroidCommand (Pilot and Plan)"; the "waiting for you"
   notification (`ActivityNotifier`, `WaitingNotices`).
4. Next, none started: a device check of the real flow (a bot turn, Pilot, a VictorBots request, an orchestrator command,
   the notification); fleet-job events from `core-fleet-worker` into the same feed; answering an approval from the list.
   Persisting the feed is deliberately deferred (nothing runs while the app is closed, and the redactor is best-effort).
5. Then decisions 1 and 2 in §5, and plan submission from VictorBots to the coordinator.
