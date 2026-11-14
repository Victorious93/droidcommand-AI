# PC-hosted prompt cache — decision (2026-11-14)

**No code change in DroidCommand's PC provider path.** Evidence and the client/server contract live in VictorLinux `docs/caching.md` (+ raw data `docs/evidence-cache/probe.json`),
measured live against the exact llama.cpp build VictorLinux pins (b12375, `9d25e00`): the server's own prefix reuse and RAM prompt cache already reuse ~82 % of prompt tokens over a 6-turn
conversation, including two interleaved conversations on ONE slot; `id_slot` pinning gave identical results (no benefit) and could only add waiting behind a busy slot.
What this means for DroidCommand: keep sending the **full canonical history every turn** (`ChatSession.sendPcHost` already does, including tool exchanges), send no `id_slot`/`cache_prompt`
hints, keep cache capability separate from tool capability and inference location, and treat any cache miss/restart/eviction as an ordinary slower turn. Streaming, cancellation, the
no-retry-after-partial-output rule, tool allowlists/approvals/audit and the inference policy are untouched. Not traced/changed this session: Plan, Pilot/Forge, Assist pop-ups and
"Remember this chat" entry points (no new send path was added, so none was altered); no live DroidCommand→server run was made (the probe used a direct HTTP client).
