# Notes Studio and System Toolbox

Both screens live under **Advanced**. Testing so far: unit tests and emulator screenshots in CI. These features have **not** been tested on a real phone.

## Notes Studio (Advanced → Notes Studio)
- **AI context files**: edit CLAUDE.md, AGENTS.md, .cursorrules and copilot-instructions.md. They are stored in the vault under `AI Context/`. You get templates, a linter (it flags empty files, likely secrets, duplicate headings, placeholders, long lines, missing headings and missing recommended sections), and export to the app's external files folder `ai-context-export/`.
- **Prompt Vault**: prompts are saved as `Prompts/<category>/<title>.md`, and you can search, save and delete them.
- **Editor**: switch between edit, split and preview, with a formatting toolbar. Mermaid diagrams render **offline** from the bundled `assets/mermaid/mermaid.min.js` (Mermaid 11.17.2, MIT license, see `third_party/mermaid`). The WebView blocks every network request and every non-asset request, and runs under a strict CSP.
- **Share on Wi-Fi**: a read-only LAN viewer.
  - It is **off by default** and starts only when you tap it.
  - It requires a password of at least 8 characters (HTTP Basic auth, compared in constant time).
  - It binds only to the Wi-Fi/Ethernet IPv4 address and rejects any client that is not on the LAN or loopback.
  - It allows only GET and HEAD.
  - It stops when you leave the screen.

## System Toolbox (Advanced → System Toolbox)
It runs through Shizuku, or through root if `su` is present. Root is detected by checking that the su file exists; su is never run during detection. Tabs: Shell, Apps (list, permissions grant/revoke, force-stop, clear, APK backup), Logcat (filter and save), Settings get/put, root Files, Device info, and Reboot.

Safety:
- Every destructive or system-changing action, and every command sent through root, opens a confirmation dialog that shows the exact command. Nothing runs automatically.
- The AI tool `shizuku_root_system_toolbox` is gated by "Let the assistant use the toolbox". The gate is **OFF by default**, and turning it on needs its own confirmation. Even with the gate on, every call asks for approval and shows the exact command. The AI has no free-form shell and no reboot.
- `externalControlEnabled` stays OFF, cert gating stays fail-closed, and the fleet entry-point list is unchanged.
