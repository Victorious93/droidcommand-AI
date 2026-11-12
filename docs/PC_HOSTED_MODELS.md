# PC-hosted models (Ollama or an OpenAI-compatible server)

Added 2026-11-11. Status and what was actually run: `docs/AUDIT_2026-09-05.md`, addenda "2026-11-11b" (chat) and
"2026-11-11c" (helper-bot tools).

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
| Check tool support, Ollama | `POST /api/show` `{"model": …}`, read `capabilities` | Ollama `docs/openapi.yaml` (`ShowResponse.capabilities`); `types/model/capability.go` (`tools`) |
| Check tool support, OpenAI-compatible | `GET /props`, read `chat_template_caps` | llama.cpp `tools/server/README.md`; `common/jinja/caps.h` |
| A helper bot's tools | `tools` on the chat request; calls come back as `tool_calls`, results go back as `tool` messages | OpenAI OpenAPI spec (`ChatCompletionMessageToolCall`, `ChatCompletionRequestToolMessage`); Ollama `docs/api/openai-compatibility.mdx` (Tools) |

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

## Helper-bot tools on a PC-hosted model

A helper bot (Bot maker → Run) can use its allowed tools while Chat is on a PC-hosted model, when the model can call
tools. Listing a model says nothing about that, so the app asks separately and shows the answer in Settings →
PC-hosted models and in the Chat header:

| Status shown | Meaning | Tools offered |
|---|---|---|
| Tools: supported (reported by the server) | Ollama lists the `tools` capability for the model, or llama.cpp's server reports `supports_tools` and `supports_tool_calls` for its loaded model | yes |
| Tools: not supported by this model (reported by the server) | the server answered and said no | no |
| Tools: not confirmed | not checked yet, the server was unreachable, or it is a server with no such report (LM Studio, vLLM, an old Ollama) | no |
| Tools: offered because you switched them on | the "Offer tools anyway" switch, for a server that cannot report it | yes |

The check runs when a model is picked and from the *Check tool support* button. It reads metadata only: no chat
request is made and nothing is generated. What it learned belongs to that model; picking another model resets it and
the switch.

What happens in a turn:

- Only the bot's allow-list is offered. Every call goes through the same path as on a cloud provider: the bot's
  allow-list, then the security policy, the approval prompt for anything that is not read-only, and the audit log.
- A call to a tool that was not offered is answered with an error and never reaches the approval prompt. A denied
  approval is answered with an error too. In both cases the model is told and continues.
- Several calls in one reply are run in order. Arguments that are not valid JSON reject the whole reply: nothing is run.
- At most 4 rounds of calls per message, at most 8 calls per reply.
- Stop is honoured between two actions. A request is never retried. If the turn fails or is stopped after an action
  already ran, the message names the actions that ran and says that sending the message again may run them again.
- If tools were switched on by hand and the server refuses them (Ollama: "does not support tools"; llama.cpp:
  "tools param requires --jinja flag"), the reply says so, nothing is run, and the request is not silently repeated
  without tools.

PC setup for tools: with Ollama, use a model whose page lists tool support. With llama.cpp's server, tools need the
Jinja template engine (on by default in the pinned version; `--jinja` on older ones) and a chat template that defines
tool calls; `--chat-template-file` can supply one.

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

## Limits

- Plan, the assist pop-ups and "Remember this chat" use the PC only under an inference policy
  (`docs/INFERENCE_POLICY.md`: "My PC only", "Prefer PC, then this device", …). With "Use the model I pick" they
  behave as before and do not use the PC server.
- Tool support cannot be confirmed for servers that do not report it; those need the owner's switch.
- With llama.cpp's server the report describes the model the server has loaded, not the model name the phone sends.
- No model download/pull from the phone, no server discovery on the network: you type the address.
- Reasoning-only output (a model that returns no `content`) is shown as an empty reply.

## Checking it yourself against your own server

```
PC_HOST_LIVE_URL=http://192.168.1.20:11434 PC_HOST_LIVE_KIND=ollama \
  ./gradlew :core-llm-factory:test --tests '*PcHostLiveServerTest*' -i
```

Runs the app's probe and chat path (JVM, same transport class as the phone) against that server: list models, stream
a reply, a missing model, stop mid-reply. `PC_HOST_LIVE_KIND=openai-compatible`, `PC_HOST_LIVE_MODEL`,
`PC_HOST_LIVE_KEY` are optional. One of its tests checks tools: it asks the server for tool support and, if the server
reports it, sends a tool exchange (a call, its result, the follow-up) and a helper-bot turn. It is skipped when the
server does not report tool support.
