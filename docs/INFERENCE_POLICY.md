# Inference policy: where the app may send text for a reply

Added 2026-11-12. Status and exactly what was run: `docs/AUDIT_2026-09-05.md`, addendum "2026-11-12a".
PC server setup: `docs/PC_HOSTED_MODELS.md`. On-device models: `docs/ON_DEVICE_GGUF_CHECKLIST.md`.

## The five choices

Chat → Options → **Where replies come from**.

| Policy | Tried, in this order | Never receives anything |
|---|---|---|
| Use the model I pick (default) | Whatever is picked in Chat, as before this feature existed | n/a: nothing is restricted |
| This device only | The on-device model | Your PC, any internet service |
| My PC only | A model established to run on your PC | This device's model, any internet service |
| Prefer PC, then this device | PC, then the on-device model | Any internet service |
| PC, then this device, then cloud | PC, on-device model, then the cloud provider picked in Chat | n/a |

The last one asks for confirmation before it is saved. A saved value of "cloud fallback" without that confirmation
behaves as "Prefer PC, then this device". Choosing any other policy withdraws the confirmation.

**Migration.** An install with no policy file keeps "Use the model I pick": nothing changes until the owner picks a
policy. A policy file that cannot be read, or names a policy this version does not know, is read as "This device only"
(the strictest), never as one that allows more. File: `files/inference-policy.properties`. Chat's existing picks
(`chat_prefs`: provider, `local_model_id`, `pc_host_kind`) are not rewritten; under a policy they supply the on-device
model and PC server until one of each has been picked while a policy is on, which is then remembered in the policy
file (Chat's own pick holds only one of the two at a time).

## What it applies to

| Feature | Under "Use the model I pick" | Under one of the four policies |
|---|---|---|
| Chat, helper-bot Chat, bot-maker "Run in Chat", voice and wake-word requests (all go through `ChatViewModel.send`) | Unchanged | `ChatSession.sendRouted`: the policy decides; the reply shows where it came from |
| A slash command naming a cloud provider (`/claude …`) | Unchanged | Refused before a provider is built, unless cloud fallback is on |
| Plan (Forge objectives) | Cloud provider picked in Chat | `PolicyInference.planner`: needs a tool-calling model, so an on-device model never qualifies and a PC model only when its server reports tool support. Otherwise refused with the reason |
| Code assist / Companion assist pop-ups | Chat's last pick (cloud or on-device) | `PolicyInference.ask`: the policy decides |
| "Remember this chat", auto-remember | Needs a cloud turn first | Extraction is sent where the policy says; never to a cloud provider unless cloud is allowed |
| Web search toggle | Unchanged | Not run unless cloud is allowed (it sends the message to a search service); the reply says so |
| `ask_ai` automation tool (always a cloud provider) | Unchanged | Refused unless cloud is allowed |
| Pilot | Runs one named tool directly; it uses no model | Same: nothing to route |
| Document Q&A embedding | On this device | Same |

Not covered: a helper bot's MCP servers are tools reached over the network, not inference; they keep their own
switch (Advanced → Extra AI tools). Notes Studio, n8n and Fleet features make no model request.

## How a place is chosen

`RouteResolver` (core-llm-factory, `ai.droidcommand.llm.routing`) turns the policy and the saved picks into
candidates and hands them to the existing `DefaultAiProviderSelector` (`explain`, added next to `selectProvider`),
which filters and ranks them. The reasons shown to the owner are the selector's. Fallback between candidates uses
`ModelRouter.fallsBackOn`, the model router's own rule (network, timeout, unavailable: yes; rejected key, bad
response, stop: no).

Checked before anything is sent:

- **Allowed by the policy.** Nothing is contacted for a place the policy does not allow. Under "This device only" no
  request of any kind is made.
- **Set up.** An on-device model is selected and its file is present; a PC server and model are saved; a cloud key
  exists.
- **Reachable, and where the model runs.** One or two short requests to the PC (`PcHostClient.inspect`):

  | Server | Asked | Establishes |
  |---|---|---|
  | Ollama | `POST /api/show`, `GET /api/ps` | Tool support (`capabilities` contains `tools`), served window (`context_length` of the loaded model, else `num_ctx`, else Ollama's 4,096 default capped at the trained length), cloud-backed (`remote_host` / `remote_model`, or a `cloud` tag) |
  | llama.cpp `llama-server` | `GET /props` | Tool support (`chat_template_caps.supports_tools`), window (`default_generation_settings.n_ctx`), runs on the PC (`model_path` is a file there) |
  | Any other OpenAI-compatible server | `GET /v1/models` | Reachability only |

  A PC model counts as "your PC" only when the server establishes it. A cloud-backed model, or one whose location
  the server does not report, is a cloud recipient: refused under the three local policies, and with cloud fallback
  tried last, after this device.
- **Fits.** The whole request is estimated: system text, history, the new message, retrieved content attached to it
  (document excerpts, memories, notes, web results), tool schemas, this turn's tool calls and results, and 512 tokens
  kept for the reply (at most half the window). Estimates err high (`ContextFit`'s rule; there is no tokenizer on
  this side). History is shortened oldest-first for a smaller window and the reply says how many messages were left
  out; the saved conversation is not shortened. A request that cannot fit even with no history is not sent to that
  place. An endpoint that does not report a window (cloud, unknown servers) is sent the whole request.

## What a reply shows

Under the reply, for example:

> Answered by This device, model Tiny. Policy: Prefer PC, then this device. My PC (Ollama at http://192.168.1.20:11434)
> was not used: PC-hosted model: could not reach http://192.168.1.20:11434. … Request about 571 tokens (system 0,
> history 0, message 27, retrieved content 0, tool schemas 0, tool results 0, reply reserve 512, overhead 32) for a
> window of 2048 tokens.

A refusal lists every place the policy allows with the reason each could not serve the request, and what to change.

## Tools, fallback and repeats

- A helper bot's tools are offered only to a place that can call them. On-device models cannot; a PC model can when
  its server reports it; the reply says when they were not offered and what to do.
- Calls run through the bot's allow-list (`ScopedToolRunner`), then `SecureToolExecutor` (policy, approval prompt,
  audit log), one at a time, in the order the model asked.
- If the place that ran a tool then fails, the next allowed place is given the completed calls and results. An
  identical call from it is answered with the stored result and is not run again.
- If the whole turn fails or is stopped after a tool ran, sending **the same message again** reuses the stored
  results. This is kept in memory only: it is lost when the app process ends, when another message is sent, or when
  the chat is switched or reset.
- Text shown from a place that then failed is cleared before the next place writes. A stopped or failed turn saves
  nothing.

## Checking it yourself

```
# Fake backends, recording transports (what CI runs):
./gradlew :core-llm-factory:test --tests '*RoutedChatTest*' --tests '*PcHostToolsTest*' --tests '*PolicyInferenceTest*'

# A real model server (real inference); see RoutedLiveServerTest for the variables:
ROUTED_LIVE_URL=http://192.168.1.20:11434 ROUTED_LIVE_KIND=ollama ROUTED_LIVE_MODEL=qwen2.5:0.5b \
  ./gradlew :core-llm-factory:test --tests '*RoutedLiveServerTest*' -i

# The real on-device engine on a Linux host, with networking removed (root):
core-llm-local-android/scripts/host-routed-flow.sh
```

## Limits

- The one-prompt features (assist pop-ups) report the first eligible place, or the list when there are several:
  `ModelRouter` does not say which provider of a chain answered.
- A server other than Ollama or llama.cpp cannot be used under the three local policies (it does not establish where
  the model runs). There is no "I vouch for this server" switch.
- Cloud turns still use the flat tool form (one call per reply, no ids), as before.
- Token figures are estimates. A server with a smaller real window than it reports still returns its own error.
