# UX audit — first-time owner (2026-10-31)

Audit of every `:app` screen and the current navigation, written so a
non-developer can see *why* the app does not make sense on first launch.
This is the before-state on `main` at `e20190c` (PR #261 fleet bind +
PR #260 GGUF catalog). The first-run / four-tab work that follows this
document is the repair, not a rewrite of this list.

**Audience:** Don, the owner. Not a developer.
**Rule used here:** if a first-time user has to already know an internal
word to finish a job, that word is a defect.

Nothing in this file changes security. External control stays off unless
the owner turns it on. Destructive actions still need a confirmation.
Certificate checks and the 13 included / 4 excluded inbound app lists
stay as they are.

---

## 1. What the app is for (and why that is hidden)

DroidCommand AI is a private AI chat on the phone, plus a home screen
for the owner's other Victorious apps (the "fleet"). A first launch
does not say that.

- The first screen is **Chat**, titled "DroidCommand", with no sentence
  that this is the place to talk to an on-device or cloud model.
- Chat is empty and immediately warns that send is "refused" until an
  API key or a `.gguf` model exists. A first-time user has never heard
  of either.
- The thing that *is* this product's other half — talking to RootForge,
  Termux / VictorSuite, HackerAI, Flash, and the rest — lives under
  **Tools → Fleet**, behind a six-tab bar and a second "Fleet
  Orchestrator" button. Don would not find it.
- There is no first-run guide. Permissions are asked later, in the
  middle of a task, with no "why".

---

## 2. Navigation (the biggest problem)

### Top bar — Mode 1 / Mode 2

Two buttons sit above every screen: the Pilot engine and the Forge
engine, labeled "Mode 1" and "Mode 2" unless the user renamed them in
Settings.

- A first-time user does not know what an "engine" is.
- Tapping them *leaves Chat* and opens Pilot or Plan, so the top bar
  is a hidden second navigator.
- The second bottom-bar tab *also* opens whichever mode is selected.
  Two controls do the same job.

### Bottom bar — six tabs

`Chat` · `<current mode label>` · `Memory` · `Tools` · `Logs` · `Settings`

- Six tabs on a phone is already cramped; labels truncate.
- **Fleet is not a tab.** It is a button on Tools.
- **Models is not a tab.** It is a Settings row and a Chat options
  button, added in PR #260.
- Memory, Logs, Pilot, Forge, Metasploit, SET, Notes, Automation,
  Clipboards, and Coding are all first-class or one tap from Tools.
  That is a developer map, not a product map.

### Start destination

`Routes.CHAT`. A new install lands on a chat that cannot send.

### Secondary routes the user can already reach

| Route | Screen title | How you get there | First-time problem |
|---|---|---|---|
| `chat` | DroidCommand | Tab | Empty; "refused" jargon; model/key buried |
| `pilot` | Mode 1 / Pilot | Top bar or tab 2 | "One tool" / backend notes |
| `plan` | Mode 2 / Forge | Top bar or tab 2 | "Objective", plan sequence |
| `memory` | Persistent Memory | Tab | Graph / entity types |
| `tools` | Tools | Tab | Fleet + security + five more products |
| `tools/metasploit` | Metasploit | Tools | Kali / proot / RUN_COMMAND |
| `tools/setoolkit` | Social-Engineer Toolkit | Tools | "No program is connected" |
| `tools/clipboards` | Clipboards | Tools | Fine, but not first-run |
| `tools/automation` | Automation keys | Tools | "keys" = saved commands |
| `tools/coding` | Coding session | Tools | "stand-in", "paired tunnel" |
| `tools/notes` | Notes | Tools | Vault / plugins |
| `tools/fleet` | Fleet (home) | Tools | Status words a developer wrote |
| `tools/fleet/pairing` | Fleet (pairing) | Fleet home | EXTERNAL_NODE, AIDL, cert SHA-256 |
| `tools/fleet/orchestrator` | Fleet Orchestrator | Fleet / Tools | "bind", "envelope", "same-signing" |
| `models` | On-device models | Settings / Chat | Dense; empty state is a grey line |
| `settings` | Settings | Tab | Mode names, keys, remote-control jargon |
| `help` | Help / Manual | Settings / Tools | Remote-control manual, not "what is this app" |
| `logs` | System Logs | Tab | Audit events |

---

## 3. Screen-by-screen

### Chat

- Title is the product name, not "AI Chat".
- Empty copy mentions Library, Memory, and "refuse a send".
- Banner: "No API key for … Send is refused until then." Friendly
  intent, hostile wording.
- Provider / model / import live in a header subtitle panel. A new
  user will not open it.
- Importing a model from Chat and managing models on a separate
  Models screen is two doors to the same room.

### Pilot (Mode 1) and Forge / Plan (Mode 2)

Useful later. Confusing as chrome. They are advanced "ask the app to
do one thing" / "run a plan" surfaces. They should not sit on every
screen.

### Memory

"Persistent Memory" and entity types. Fine as a Settings extra. Not a
primary tab.

### Tools

A junk drawer: Fleet (the actual product), Metasploit, SET, clipboards,
automation, coding, notes. Copy still says EXTERNAL_NODE, "Not LIVE",
"live bind pending".

### Fleet home (PR #242 / #258 / #261)

One card per catalogued app — the right shape. The words are wrong:

- Status labels include `PENDING`, `PENDING-REPACKAGE`, `Not paired`,
  `Awaiting status`, `Reachable`.
- Reasons include "Bind refused (same-signing)", "Bind refused (cert)",
  "Bridge missing", "Peer control off".
- Banner: "Live status from the phone is on for TRUSTED paired nodes."
- Empty CTA: "Pair a node to get live status" / "Open pairing".
- Buttons: "Refresh list", "Pair and manage nodes", "Open fleet
  commands".
- Tapping HackerAI / Pentest jumps to the orchestrator. Tapping a
  pairable app jumps to a second Fleet screen that repeats the list
  in developer language. Tapping others opens a dialog that still
  says "node" and "signing key".
- There is no one-tap Pair with steps, no "Test connection" on the
  card, and no short help per app. The long `FleetCatalog.summary`
  strings are internal notes (AIDL, DependencyGuard, protocol_major).

A first-time user needs four statuses only:

1. **Connected**
2. **Installed, tap to pair**
3. **Not installed**
4. **Blocked:** reason + what fixes it

### Fleet pairing screen

Title is also "Fleet". Intro paragraph names AIDL, DependencyGuard,
external nodes, Messenger, Kali gates, "Nothing is LIVE". Pair
confirm shows package id + "observed signing-cert SHA-256". Buttons
include Capabilities, Status, "Bring up (then poll status)".

Certificate checking must stay. The *words* on screen must not.

### Fleet Orchestrator

A command console. Keep it, hide it under Settings / a card action,
and drop "Orchestrator" from the title the owner sees.

### Models (PR #260)

Real work: pick a `.gguf`, scan folders, download a free catalog,
search Hugging Face. For a first run it is too much at once.

- Title "On-device models" is accurate but not a tab.
- Current model is a parenthetical `(active)` on a row, easy to miss.
- Empty state is "No models imported yet." with no two-button choice.
- Catalog copy mentions "Hub LFS values", SHA-256, RAM verdicts.

Needed: current model at the top; if none, two buttons — **Pick a
.gguf from storage** and **Browse catalog**.

### Settings

Starts with Mode 1 / Mode 2 rename. Then API keys, GGUF, Hugging Face,
web search, voice, Help (remote-control manual), Remote Control
(envelope / nonce / ttlMs / YOLO), Shizuku, device tools, chat
library, notes, advanced toolbar.

Helpful later. Overwhelming first. Help should be "what is this app",
not a remote-control spec.

### Help / Manual

`RemoteControlManual` — frozen envelope, triggers, VictorSpoof
isolation. That is a reference for a feature the owner has not met.

### Logs

Audit log. Keep, not a tab.

### Secondary tool screens

Metasploit / SET / Automation / Coding / Notes / Clipboards are
real and should stay reachable. None of them belong on the first-run
path. Their empty/error copy should stay honest and become friendlier
where it currently says "refused" or names backends.

---

## 4. Internal words that must not appear on screen

Do not show these to the owner (code, comments, and docs may keep
them):

- `EXTERNAL_NODE`, `BOUND_COMPANION`, `HOST_PROCESS`, `AIDL`
- `envelope`, `pin`, `SHA-256` as a primary label
- `TRUSTED`, `PENDING-REPACKAGE`, `LIVE`, `Not LIVE`
- `Messenger`, `DependencyGuard`, `same-signing` as a status
- `node` when we mean "app"
- "refused" when we mean "needs a model or a key first"

Certificate *checks* stay. We describe a block as "this install does
not match the one you already trusted" plus the fix.

---

## 5. First-run gaps

A first-time user should be able to:

1. Learn what the app is (chat + your other Victorious apps).
2. Pick or download a model so Chat can answer.
3. See the fleet list and pair an installed app, or skip.
4. Optionally grant microphone, notifications, and all-files access,
   each with a reason, all off-by-default until tapped.

None of those four steps exist as a guided path today.

---

## 6. What we will change (and what we will not)

**Change**

- Four tabs: **Fleet**, **AI Chat**, **Models**, **Settings**.
- First-run setup covering the four steps above, skippable, replayable
  from Settings.
- Fleet cards with the four plain statuses, a Pair flow with steps,
  Test connection, and a short help line per app.
- Models: current model up top; empty state with the two buttons.
- "What is this?" on each primary screen; friendlier errors.
- Pilot, Forge, Memory, Tools, Logs, Help stay in the app, reached
  from Settings (and Chat options where they already live).

**Do not change**

- External control default OFF.
- Destructive confirmation (including Metasploit / SET / Bring up).
- Cert gating and pairing confirmation.
- 13 included / 4 excluded inbound lists and `<queries>`.
- Password-pairing work on `cursor/password-pairing-b61a` (PR #262).
- No secrets committed.

---

## 7. Honesty

This audit is from source on `e20190c`. It was not walked on a phone.
Emulator screenshots, if CI produces them after the repair, are of the
*new* chrome, not this before-state.
