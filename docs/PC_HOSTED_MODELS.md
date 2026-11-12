# PC-hosted models (Ollama or an OpenAI-compatible server)

Added 2026-11-11. Status and what was actually run: `docs/AUDIT_2026-09-05.md`, addendum "2026-11-11b".

## Two workflows — do not mix them up

| | PC-hosted (this document) | On-device (`docs/ON_DEVICE_GGUF_CHECKLIST.md`) |
|---|---|---|
| Where the model file is | On the PC. Nothing is copied to the phone. | Imported onto the phone as a `.gguf` (Models tab). |
| Who writes the reply | The PC. Every message, with the conversation so far, is sent to it. | The phone. |
| What it needs | The phone can reach the PC: same Wi-Fi, or a VPN. Not internet. | Nothing. No network at all. |
| Set up in | Settings → PC-hosted models | Models tab |

"Server-local" and "cloud-backed" are a third distinction, inside PC-hosted: an Ollama server can list models it only
forwards to ollama.com. Those need internet **on the PC** and send the chat to that service. The app marks them
"Cloud-backed" in the model list, in the chat header, and on every reply. An OpenAI-compatible server's model list
has no field for this, so its models are shown as "Location not reported by this server".

## What the phone sends

| Action | Request | Source |
|---|---|---|
| Test connection / list models, Ollama | `GET /api/tags`, then `GET /api/version` | Ollama `docs/openapi.yaml` (`ListResponse`, `ModelSummary.remote_host` / `remote_model`, `VersionResponse`) |
| Test connection / list models, OpenAI-compatible | `GET /v1/models` | OpenAI OpenAPI spec (`ListModelsResponse`) |
| Chat, both kinds | `POST /v1/chat/completions` with `"stream": true`, read until `data: [DONE]` | Ollama `docs/api/openai-compatibility.mdx`; OpenAI spec |
| Optional key | `Authorization: Bearer <key>` | Ollama `docs/api/authentication.mdx`; llama.cpp server README (`--api-key`) |

Ollama's chat goes through its OpenAI-compatible endpoint, so both kinds share the existing `OpenAiLlmProvider`.
Consequence: the phone cannot set Ollama's context size per request (Ollama's own document says the OpenAI API has no
way to do it). Set it on the PC, see below.

Timeouts: 8 s to connect, 180 s of silence once connected (a PC loads a large model before the first word), no retry.
Stop closes the connection.

## PC setup: Ollama

Checked against the Ollama repository's `docs/faq.mdx`, `docs/api/*.mdx` and `docs/openapi.yaml` at commit `8a89b76c`
(2026-11-11). `docs.ollama.com` itself was not reachable from the session that wrote this.

1. Install Ollama and get a model: `ollama pull <model>` (needs internet once; after that the model is on the PC).
2. Make it listen on the network. By default Ollama binds `127.0.0.1` port `11434`, which the phone cannot reach.
   Set `OLLAMA_HOST=0.0.0.0:11434`:
   - **Windows:** quit Ollama from the task bar; Settings → search "environment variables" → *Edit environment
     variables for your account* → add `OLLAMA_HOST` = `0.0.0.0:11434`; start Ollama again.
   - **macOS (app):** `launchctl setenv OLLAMA_HOST "0.0.0.0:11434"`, then restart the Ollama app.
   - **Linux (systemd):** `systemctl edit ollama.service`, add under `[Service]`
     `Environment="OLLAMA_HOST=0.0.0.0:11434"`, then `systemctl daemon-reload && systemctl restart ollama`.
3. On the PC, check: `curl http://localhost:11434/api/version` and `curl http://localhost:11434/api/tags`.
4. On the phone: Settings → PC-hosted models → Ollama → type the PC's Wi-Fi or VPN address (for example
   `192.168.1.20`; port 11434 is added) → *Save and test connection* → pick a model → *Use in AI Chat*.

Optional, from the same FAQ:

- Local models only: `OLLAMA_NO_CLOUD=1`, or `"disable_ollama_cloud": true` in `~/.ollama/server.json`; restart.
- Larger context: `OLLAMA_CONTEXT_LENGTH=8192` (default is 4096 tokens).
- Keep the model loaded between messages: `OLLAMA_KEEP_ALIVE` (for example `-1`).

Not from Ollama's documentation, but needed in practice (general networking, unverified here): allow inbound TCP 11434
in the PC's firewall for the private network only, and stop the PC from sleeping while you use it.

**Security.** Ollama's local API has no authentication ("The local API at `http://localhost:11434` does not require
authentication"). With `0.0.0.0` anyone on the same network can use it. Do not forward the port on your router. To
limit it to a VPN, bind to the PC's VPN address instead of `0.0.0.0`.

## PC setup: OpenAI-compatible server (example: llama.cpp `llama-server`)

Flags from llama.cpp's `tools/server/README.md`:

```
llama-server -m /path/to/model.gguf --host 0.0.0.0 --port 8080 --api-key <a-key-you-choose>
```

`--host` defaults to `127.0.0.1`. Always pass `--port`: the documented default differs between llama.cpp versions.
`--api-key` is optional; if you set it, save the same key on the phone (it is stored in the encrypted vault and sent
only to this server). On the phone: Settings → PC-hosted models → OpenAI-compatible server →
`http://<pc-address>:8080`. A trailing `/v1` is accepted.

LM Studio, vLLM and similar servers use the same two endpoints; their own setup was not checked for this document.

## Addresses the phone accepts

- `https://` anything.
- Plain `http://` only to loopback, `10.x`, `172.16–31.x`, `192.168.x`, link-local, `100.64.0.0/10` (Tailscale /
  Headscale), and IPv6 `fc00::/7`. A host **name** over plain HTTP is resolved first and refused if it points to a
  public address. Plain HTTP is not encrypted: use it on your own network or VPN only.
- No user name/password in the address; cloud-metadata addresses are refused.

## What each failure says

| Situation | Message starts with "PC-hosted model:" and says |
|---|---|
| PC asleep, off, wrong address, other network, server not running or bound to localhost only | could not reach `<address>` … not about internet access |
| Connected, then silence for 180 s | accepted the request but sent nothing … may still be loading a large model |
| Model not on the server (HTTP 404) | does not have the model … `ollama pull <model>` |
| Key missing or wrong (HTTP 401/403) | refused the request as not authorised |
| PC lost mid-reply | connection was lost while the reply was arriving; partial reply discarded |
| Cloud-backed model, PC has no internet (HTTP 502) | your PC answered, but could not reach the internet service behind the cloud-backed model |

In every case nothing is saved and the message returns to the input box, so it can be sent again.

## Helper-bot tools on a PC model (2026-11-12)

A helper bot's tools are offered to a PC model when the server reports that the model can call them: Ollama lists
`tools` in `/api/show` `capabilities`; a llama.cpp server reports `chat_template_caps.supports_tools` in `/props`.
The server is asked only when a bot with tools is running (plain chat sends exactly what it sent before).

- Calls and results are sent in OpenAI's structured form: the assistant message carries every call with the server's
  own id and its argument text exactly as streamed; each result is a `tool` message with the matching `tool_call_id`.
  Several calls in one reply are run in order. A call with no id gets one made on the phone.
- A call whose arguments are not a JSON object, or that names no tool, is not run; the model is told.
- Every call goes through the bot's allow-list, then the normal approval dialog and audit log. Denied, refused and
  revoked calls run nothing and the model is told.
- Stop during a tool round: no further call is run, nothing is saved.
- Not offered, with the reason in the reply: a model Ollama lists no `tools` for; a llama.cpp model whose chat
  template has no tool support; any other OpenAI-compatible server (it does not report this).

Policies ("My PC only", "Prefer PC, then this device", …) are in `docs/INFERENCE_POLICY.md`.

## Limits

- Plan, the assist pop-ups and "Remember this chat" use the PC only under an inference policy
  (`docs/INFERENCE_POLICY.md`); with "Use the model I pick" they behave as before.
- No model download/pull from the phone, no server discovery on the network: you type the address.
- Reasoning-only output (a model that returns no `content`) is shown as an empty reply.

## Checking it yourself against your own server

```
PC_HOST_LIVE_URL=http://192.168.1.20:11434 PC_HOST_LIVE_KIND=ollama \
  ./gradlew :core-llm-factory:test --tests '*PcHostLiveServerTest*' -i
```

Runs the app's probe and chat path (JVM, same transport class as the phone) against that server: list models, stream
a reply, a missing model, stop mid-reply. `PC_HOST_LIVE_KIND=openai-compatible`, `PC_HOST_LIVE_MODEL`,
`PC_HOST_LIVE_KEY` are optional.
