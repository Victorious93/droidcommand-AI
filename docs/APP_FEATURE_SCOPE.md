# App feature scope: keyboard, clipboards, automation, AI quick-ask, local GGUF

**Status: S1–S3 built, see section 7. S4 not started.** Date: 2026-10-12. Written before implementation, following the
scope-first practice of `docs/VOICE_PHASE_SCOPE.md` and `docs/KNOWLEDGE_GRAPH_PHASE_SCOPE.md`. Sections 1–6 record the
scope as it was decided; they are not updated to match the code.

Evidence labels: **[Certain]** checked directly in the repo or the APK; **[Likely]** strong inference, not
tested; **[Guessing]** assumption.

---

## 1. The reference APK

- File: `app-debug.apk` inside an uploaded zip, 17,146,394 bytes, sha256
  `ec5049b6c2f574a818f949808c0b1645c31a17a13caca6d004d6a7dddba24e96`. **[Certain]**
- Package `ai.droidcommand.app`, versionName `0.1.0-phase0`, targetSdk 36, 23 dex files, native libs for four
  ABIs. **[Certain]**
- Its app-package screens: Chat, Memory, Settings, Tools (with Metasploit and SET screens), approval prompts,
  navigation. **[Certain]**
- Screens in this repo but **not** in the reference: **Plan** and **Logs** tabs. The reference is an older build
  of this app, not a finished target. **[Certain]**
- No app-package class in the reference has a keyboard, clipboard, macro, automation or slash-command name.
  Keyword hits in its dex come from libraries (for example Compose `KeyboardOptions`) and from `core-agent`'s
  macro classes. **[Certain]**
- **I cannot render this APK** (no emulator or device here), so I cannot compare its visual layout. Screenshots
  would close that gap. **[Certain]**

## 2. Requested features against this repo

| Requested | What exists in the repo | Gap | Status |
|---|---|---|---|
| Expanded settings with an advanced list | Settings has API-key, web-search-key and voice sections (`ui/settings/SettingsScreen.kt`, `VoiceSettingsSection.kt`). No advanced section. | Content of the "advanced" list is an owner decision | MISSING |
| Toggle buttons you can add or remove | Toolbar and options-panel toggles are fixed in code: web search, speak mode, memory, microphone, document attach (`ui/chat/ChatScreen.kt`) | No configurable layout or persistence | MISSING |
| Two clipboards: public and private, move clips between them | Nothing in `app/`. `core-tools-android` defines clipboard get/set on `DeviceController`, implemented only by `NullDeviceController`. `SystemTools` is not registered anywhere in `app/`. | Whole feature | MISSING |
| Keyboard: change keys, move keys, add keys | No keyboard code. No custom keyboard, no `InputMethodService`. | Whole feature. Design depends on decision D1 | MISSING |
| Automation keys for Linux scripts | Macro engine exists in `core-agent`: `MacroExecutor`, `MacroStore` / `JsonFileMacroStore`, `MacroScheduler`. The app has no macro UI (`navigation/DroidCommandNavHost.kt:59` says so). `AppModule` registers `ShellTool` with `ShellSecurityPolicy()`, whose default `allowedExecutables` is empty, so it runs nothing. Root, Termux, Metasploit and SET tools get `Null*` executors. | Macro UI, a real execution path, an owner-maintained allowlist | PARTIAL (engine only) |
| Slash commands for AI (ChatGPT, Claude Code) | Chat sends plain text to four BYOK providers: Anthropic, OpenAI, Google, Groq (`core-llm-factory/.../CloudProviderCatalog.kt`). No slash syntax. | Command parser and the command set (owner) | PARTIAL |
| Ask a question or send commands to an AI API from the keyboard | Same four providers, keys in `AndroidSecretsVault` | Depends on D1 | MISSING (keyboard surface) |
| Run local `.gguf` models in the app | Native llama.cpp backend builds for arm64-v8a and x86_64, CPU backend, Vulkan compiled but never run on a GPU (`core-llm-local-android`). `core-llm-factory` `ChatSession.sendLocal` exists, JVM-tested with fake backends. **The app never calls `sendLocal`**, and `ChatViewModel` builds `ChatSession` without local resources. The app's only model download is the pinned embedding model. There is no import of a user's own `.gguf`. | Wiring into chat, a model import and registration path, a model picker. No on-device inference has ever run. | PARTIAL (not wired, not device-verified) |

**[Certain]** for every row above (verified against the file paths shown).

## 3. Constraints and risks

- **Automation and Linux scripts must use the existing gated path.** Every run goes through `SecureToolExecutor`
  (approval, `SecurityPolicyEnforcer`, audit log), like every other tool. Automation keys must not call
  `Runtime.exec` directly. The shell allowlist starts empty; the owner adds each executable. **[Certain]**
- **Private clipboard encryption** should reuse the Keystore pattern in `app/security/AndroidSecretsVault.kt`.
  That file's own header says it compiles but has never run on a device, so encryption is unverified on hardware.
  **[Certain]**
- **"Public" clipboard limits.** On Android 10 and later, an app can normally read the system clipboard only
  while it is in the foreground, or when it is the default input method. So the public list can capture copies
  made inside this app and pastes made while it is open, not everything copied elsewhere. A system keyboard
  (D1 option b) would change that. **[Likely]** from Android platform behaviour; not tested here.
- **Private clips leaking.** "Copy to system clipboard" puts text where other apps can read it. Private clips should
  not be copied out unless the user asks for it on each clip. **[Guessing]** this is the right default.
- **GGUF files are untrusted input.** They are parsed by native llama.cpp code. A SHA-256 recorded at import proves
  the file has not changed since import. It does not prove where the file came from; only a pinned catalog does
  that. **[Certain]** on the mechanism, **[Likely]** on the parser risk, which is the general reason for not
  loading unknown model files.
- **A system keyboard (IME) sees everything typed in every app.** It needs a careful permission and privacy design
  and cannot be verified without a device. **[Certain]** on the capability, **[Likely]** on the consequence.
- **Nothing here has run on a device or emulator.** All Android UI and native behaviour remains unverified on
  hardware. The `:app` debug APK builds (CI run #3 on the previous branch, and locally). **[Certain]**

## 4. Decisions needed from the owner

**D1 - Which keyboard?**
- (a) **In-app keyboard**, Compose. Keys, layouts, quick-ask and automation keys work inside DroidCommand AI only.
  Lowest risk; the layout logic is JVM-testable. **Recommended first.**
- (b) **System keyboard (`InputMethodService`)**. Works in every app, but the user must enable it, it has a larger
  privacy surface, and it cannot be verified here.
- (c) Both, in-app first and the system keyboard later.

**D2 - How do GGUF models get into the app?**
- (a) **User-chosen file.** Pick a `.gguf` with the system file picker, copy it into app storage, record its SHA-256
  and label it "origin not verified". **Recommended**, given the owner's need for arbitrary models.
- (b) **Pinned catalog only**, as the embedding model works today. Safest, but no arbitrary models.

**D3 - Where does Linux automation run?**
- (a) **Existing shell tool, deny-all by default.** Each executable is allowlisted by the owner and each run is
  approved. **Recommended.**
- (b) **Termux bridge** (`core-termux`). Needs Termux installed on the device.
- (c) **Root** (`core-root`). Needs a rooted device. `core-root` has real executors, but the app's `AppModule` wires `NullRootExecutor`, so this path would need that wiring changed.

**D4 - UI reference.** Send screenshots of the reference UI. Without them, I can only match the screen list, not
the look.

## 5. Proposed slices

Each slice ships code, JVM tests, a passing `:app:assembleDebug`, an append-only addendum in
`docs/AUDIT_2026-09-05.md`, and an `docs/ARCHITECTURE.md` row. No PR is opened until the owner asks.

- **S1 - no decision needed.** Configurable chat toolbar (add or remove toggles, persisted). Settings gets an
  Advanced section. Two-clipboard store: a public list and an encrypted private list, with an explicit
  "move to private" action. **Assumption [Guessing]:** the advanced list starts with the existing knobs
  (default provider and model, default toggle states, toolbar layout, clipboard size limits); the owner can add
  more.
- **S2 - needs D2.** Local GGUF: import, model registry, model picker, and routing chat to `sendLocal` when a local
  model is selected. Tested with fake backends. A device run is still needed.
- **S3 - needs D3, and the macro UI.** Automation keys on `MacroExecutor` / `MacroStore`. Every step goes through
  `SecureToolExecutor`. A slash-command parser for AI prompts, with a command set the owner defines.
- **S4 - needs D1.** Keyboard: layout model, key editor (move, add, change), quick-ask key. The system keyboard is
  built only if D1 chooses it.

## 6. Open items (owner)

- Slash-command grammar for "Claude Code" and "ChatGPT".
- Advanced settings beyond the S1 defaults.
- Whether to match the reference look (needs D4).


## 7. Implementation status (2026-10-12)

| Slice | Status | Record |
|---|---|---|
| S1: configurable chat toolbar, Advanced settings, two clipboards | Built. JVM-tested. Debug APK builds. Not run on a device. | Audit addenda 2026-10-12a to 2026-10-12d |
| S2: user-imported GGUF models in chat | Built. JVM-tested with fake backends. Debug APK builds. The native load and inference have not run on a device. | Audit addendum 2026-10-12e |
| S3: automation keys, AI slash commands, approvals that show their input | Built. JVM-tested. Debug APK builds. Not run on a device. | Audit addendum 2026-10-12f |
| S4: in-app keyboard (D1: in-app) | Not started | None |

### Choices made under "You choose" for S3

The owner asked for the next step to be chosen and built on 2026-10-12. These choices can be redirected:

- **Shell keys.** They run only from automation keys. The allowlist starts empty and is edited on the Automation keys screen. Each run asks for approval, as D3 decided.
- **Slash commands.** `/claude` (Anthropic), `/chatgpt` (OpenAI), `/gemini` (Google) and `/groq` (Groq). In chat, a command sends that one message to that provider. In an automation key, the same command is the AI action. Claude Code itself is not reachable from the app, so `/claude` uses the Anthropic API.
- **Planners.** Neither the shell tool nor the AI tool is offered to the Pilot or Forge planners. Both have an empty `allowedModes`.
- **Storage.** Keys live in their own registry file, not in `JsonFileMacroStore`, because a key needs a free-text label and that store's names are limited to `[A-Za-z0-9_-]+`. Each key still runs as a one-step macro through `MacroExecutor`.
- **Approval dialog.** It now shows the tool name and each input value, for every tool. Before this, it showed only the policy reason.

### Known limits of S3

- A shell argument is split on spaces, as the existing shell tool splits it. There is no quoting.
- An allowlist entry must equal the key's executable exactly.
- An AI key makes one request with no system prompt. It does not use web search, documents or memory.
- Nothing runs unattended. Each run waits for approval. Scheduling is not built.
