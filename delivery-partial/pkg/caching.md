# Build caching and PC-hosted prompt cache (VictorLinux)

All numbers here come from `docs/evidence-cache/` (raw JSON/env files written by `scripts/cache-bench.sh` and
`scripts/cache-probe.py`). Host: 4-vCPU Intel Xeon, 15 GiB RAM, Ubuntu 24.04, g++ 13.3, CMake 3.28, Ninja, **no GPU, no KVM, no
running container daemon** — so everything below is a *native host* measurement, not an image-chroot build, and the Vulkan
backend was **not** built or measured.

## 1. Native build cache (sccache)

### What was measured
llama.cpp `9d25e0014c4b4b316873c01f4938c0e3c687287d` (b12375, the commit the Debian edition pins; the Arch edition pins b12374 /
`9183a552…`, a different commit — see "Findings"). CPU-only profile with the image flags
(`GGML_BACKEND_DL=ON GGML_CPU_ALL_VARIANTS=ON GGML_NATIVE=OFF`, Release, `llama-server` target, `-j4`), fresh build directory every run, so
all 401 compiler invocations really execute unless the cache answers. sccache **v0.10.0**, static musl release, tarball SHA-256
`1fbb35e1…49b` equal to the checksum published beside the release asset. Exact commands are in each `*.env`.

| Run | Wall time | sccache hits / misses | Notes |
|---|---|---|---|
| 1 baseline, no cache | 236.7 s | – | |
| 4 baseline repeat, no cache | 225.0 s | – | run-to-run spread ≈ 5 % |
| 2 sccache, cold cache | 232.4 s | 0 / 401 | no measurable overhead vs baseline |
| 3 sccache, warm cache, fresh build dir | **13.5 s** | **401 / 0**, 0 non-cacheable, 0 compile failures | ≈ 17× faster than baseline; cache = 21 MiB |

Invalidation (target `ggml-base`, 9 compiles, same cache): same dir → hits; **different build directory → 9/9 hits** (my first assumption
that the path would break reuse was wrong); `CMAKE_BUILD_TYPE=RelWithDebInfo` → 0/9; `-DGGML_LTO=ON` → 0/9. So compiler/flag changes
invalidate, as they must, and relocating the build dir does not.

### What it does and does not prove
* It proves compiler-level reuse for **C/C++ on this revision** on this host. A no-op rebuild would not have shown this; these were fresh outputs.
* Not measured: Vulkan (needs `libvulkan-dev`/glslc, unavailable here), CUDA, HIP, the Arch/Debian chroots, or any ISO. The Vulkan backend
  compiles shaders through a CMake `ExternalProject` (`vulkan-shaders-gen`) whose `CMAKE_ARGS` in this llama.cpp revision do **not** forward
  `CMAKE_*_COMPILER_LAUNCHER`, so that sub-build and the glslc step stay uncached; real Vulkan benefit is therefore **smaller than the CPU
  figure and unmeasured**.
* Existing alternative not evaluated: ggml already auto-uses `ccache` when installed and no launcher is set (`ggml/src/CMakeLists.txt`,
  `GGML_CCACHE`). It would avoid a pinned download but is a distro package inside each chroot; not measured.

### How it is wired (opt-in, default OFF)
`VICTOR_SCCACHE=1 scripts/build.sh …` (Arch) or `VICTOR_SCCACHE=1 sudo debian-edition/build.sh` (Debian). Unset → the original build path, byte-for-byte.
* `scripts/sccache.pin` pins version + SHA-256; `scripts/sccache-stage.sh fetch` verifies it. If it cannot be fetched/verified, staging prints a
  warning and the build proceeds **uncached — decided before compilation**; a failing compile is never retried to dodge a cache problem.
* Launcher is passed through CMake (`CMAKE_C/CXX_COMPILER_LAUNCHER`) in both chroot scripts; compilers and per-profile flags (CPU, Vulkan, CUDA,
  HIP) are untouched; CUDA/HIP are not wrapped.
* Cache location on the host: `$VICTOR_SCCACHE_DIR` (default `~/.cache/victorlinux/sccache`); bound `$VICTOR_SCCACHE_MAX_MB` (default 2048,
  oldest-first trim before each build); purge: `scripts/sccache-stage.sh purge`. Local disk backend only — no tokens or remote store exist.
* Why files travel in/out instead of a bind mount: neither mkarchiso nor live-build can mount host paths into the chroot where compiling happens
  (and the chroot is deleted afterwards). The wrapper and cache are copied in, used, harvested.
* **Keeping it out of the ISO:** Arch — the squashfs gets `-e var/cache/victor-sccache usr/local/lib/victor-build`; Debian — the build is split
  (`lb bootstrap`, `lb chroot`, harvest + scrub + `check-clean`, then `lb binary`) and aborts on any leak. `sccache-stage.sh check-clean ROOT` is
  tested on synthetic trees (flags a leak, passes a clean tree).
* **UNVERIFIED:** none of the chroot/ISO paths above was executed (no container daemon, no live-build/mkarchiso here). The Arch template renders
  and `bash -n` passes with the flag on and off; the Debian hook and split flow are syntax-checked only.

## 2. PC-hosted inference prompt cache

Source-verified against the pinned server (`tools/server/README.md`, `server-context.cpp`, `server-chat.cpp` at `9d25e00`), then probed live with
that exact binary (`SmolLM2-135M-Instruct-Q8_0`, CPU, 4096 ctx, 6-turn conversations, full canonical history sent every turn).

### Findings (what the server already does)
| Question | Result |
|---|---|
| Prefix reuse with defaults | **82.1 %** of prompt tokens served from cache over 6 turns (1305/1590; the first turn can never hit) |
| `cache_prompt:false` | 0 % reuse, identical replies |
| Two interleaved conversations, **1 slot**, default | A 82.1 %, B 82.8 % — the server's RAM prompt cache (`--cache-ram`, default 8192 MiB) swaps them |
| Same, `--cache-ram 0` | A 3.8 %, B 4.5 % — this is what the cache is for |
| Two interleaved conversations, 2 slots, unpinned vs `id_slot` pinned | identical (82.1 % both) — **pinning added nothing** |
| `id_slot` on `/v1/chat/completions` | accepted (HTTP 200) on this revision; out-of-range wraps (source: modulo slots) |
| Restart | cache gone (`cache_n=0`), same reply text — correctness never depended on the cache |
| Management endpoints with `--api-key` | `/slots`, `/props`, `/v1/models` → 401 without key; `/health` open; `/metrics` 501 (disabled) |

### Decision
**No new affinity layer.** Native reuse already gives the whole benefit measured, including interleaved conversations on one slot, and
`id_slot` can only add head-of-line waiting behind a busy slot. (A concurrent pinned-vs-unpinned timing in the probe was confounded by the
prompt cache and is **not** used as evidence.) Changes made:
* VictorLinux (Debian edition): `cache_ram_mb` setting, default `auto` = `min(8192, ¼ of RAM left after the model and 2 GiB headroom)`, `0`
  disables, explicit value honoured, validated 0–65536; `--cache-ram N` is passed to `llama-server`. llama.cpp's own default would reserve up to
  8 GiB of host RAM regardless of the machine. Concurrency stays `parallel=1` and is **not** raised by the number of phones.
* The Arch edition's launcher is unchanged (it does not pass `--cache-ram`; it inherits the server default). Not edited because its launcher
  could not be exercised here. **Gap.**
* Disk persistence (`--slot-save-path`) stays **off**; nothing enables it.

### Client/server contract (DroidCommand ↔ llama.cpp server)
1. **Discovery:** none required for correctness. Optional `GET /props` (key required when the server has `--api-key`) reports `total_slots`; cache
   capability is advisory and is **separate** from tool support and from inference location.
2. **Hints:** none sent. No `id_slot`, no `cache_prompt` override. Unknown/unsupported caching ⇒ ordinary chat.
3. **Every turn sends the full canonical history** (including tool calls/results); the cache only shortens re-evaluation, never the logical
   context or the model's context limit.
4. **Fallback/restart/eviction:** a miss is just slower; replies are identical (verified at temperature 0, seed 1).
5. **Authorization:** `/slots`, `/props`, models endpoints need the API key; affinity (not used) would never be an authorization boundary.
6. **Observable metrics:** per request `timings.prompt_n` (evaluated) and `timings.cache_n` (reused); `GET /slots` for per-slot state.
7. **Limits of this evidence:** tiny CPU model (token accounting transfers, absolute seconds do not); no GPU, no RX 6800 XT, no VRAM figures,
  no real tool-calling model; swap/eviction under real memory pressure not exercised.
