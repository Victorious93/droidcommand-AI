# VictorSuite RUN_COMMAND — Kali first-win

DroidCommand AI is the controller hub. Kali tools run **inside VictorSuite**
(Termux + `proot-distro` Kali), never in the app process. Results come back
through `RUN_COMMAND_PENDING_INTENT`. This is **not** ADB, not root, and not
terminal scraping.

On-device `msfconsole` round-trip status: **UNVERIFIED**. No session that
wired this path had a device with VictorSuite + Kali + nmap/metasploit.

VictorSuite itself does not need a code change; the non-root PendingIntent
path already exists there.

## Request (caller → VictorSuite)

- Action: `com.termux.RUN_COMMAND`
- Component: `com.termux` / `com.termux.app.RunCommandService`
- Start via: `startForegroundService(intent)` (API 26+)
- Caller manifest **must** declare: `com.termux.permission.RUN_COMMAND`
- Extras:

| Extra | Value |
|---|---|
| `com.termux.RUN_COMMAND_PATH` | `/data/data/com.termux/files/usr/bin/proot-distro` |
| `com.termux.RUN_COMMAND_ARGUMENTS` | `{"login","kali","--","nmap","-sV","<target>"}` (or `bash -lc <pipeline>`) |
| `com.termux.RUN_COMMAND_WORKDIR` | `/data/data/com.termux/files/home` (optional) |
| `com.termux.RUN_COMMAND_BACKGROUND` | `true` (**required** to capture output; equivalent to `RUN_COMMAND_RUNNER=app-shell`) |
| `com.termux.RUN_COMMAND_PENDING_INTENT` | caller's `PendingIntent` (`FLAG_MUTABLE`) |
| `com.termux.RUN_COMMAND_COMMAND_LABEL` | optional |

Pipelines/redirection (msfconsole `-q -r`): wrap as
`arguments={"login","kali","--","bash","-lc","<pipeline>"}`.

Foreground terminal sessions paint the screen and **do not** fill the result
Bundle. Background / app-shell is mandatory.

## Response (`RESULT_OK`)

`resultIntent.getBundleExtra("result")`:

- `stdout` / `stderr` — `String`
- `stdout_original_length` / `stderr_original_length` — true size as `String`
- `exitCode` — `int`, present **only if the command actually ran**
- `err` — `int` (`0` or `-1` = launch OK; anything else = launch/validation failure)
- `errmsg` — `String`, null on success

Distinguish:

- **Command failure:** `exitCode != 0` and `err` is OK (`0` / `-1`)
- **Termux launch failure:** `err != 0`; read `errmsg`. Do **not** claim
  nmap or msfconsole ran.

Size limits: `TRANSACTION_SIZE_LIMIT=100KB`; one stream → 100KB, both → 50KB
each, `errmsg` 25KB; trimmed from the **front** (tail kept). For larger
output use `RUN_COMMAND_RESULT_DIRECTORY` (+ `RESULT_SINGLE_FILE` /
`RESULT_FILE_BASENAME`). This first slice does not implement the directory
path.

## Device config (end user, not code)

1. Set `allow-external-apps=true` in `~/.termux/termux.properties` and
   reload Termux / VictorSuite. Default is `false`; every external call is
   then rejected.
2. Grant `com.termux.permission.RUN_COMMAND` to DroidCommand AI.
3. Use the background / app-shell runner (this app always sets
   `RUN_COMMAND_BACKGROUND=true`).

## Kali bootstrap (minimal Kali lacks nmap + Metasploit)

```sh
pkg install proot-distro -y
proot-distro install kalilinux/kali-rolling --name kali
proot-distro login kali -- apt-get update
proot-distro login kali -- apt-get install -y nmap metasploit-framework
```

The distro name `kali` is the one `ProotDistroKaliEnvelope` sends after
`login`.

## Safety gates (mandatory, non-bypassable)

- Per-run confirmation must be the exact phrase
  `I confirm these targets are authorized`
- No blank / mass / subnet / CIDR / range / wildcard targets
- `scope_file` is required and must list that single host
- ACTIVE and EXPLOIT steps need a fresh per-step approval
- Never claim msfconsole ran if VictorSuite did not return an `exitCode`
