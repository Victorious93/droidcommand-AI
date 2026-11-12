#!/usr/bin/env bash
# Runs the app's own Models -> Chat classes (LocalModelRuntime, ChatSession, LocalLlmProvider, LlamaCppBackend)
# against the REAL JNI shim and llama.cpp on a Linux x86_64 HOST. See host-smoke/ChatFlow.java for what it checks.
# This proves the Kotlin and native logic end to end. It does NOT prove Android behavior: no file picker, no UI,
# no ActivityManager, no phone memory limits.
#
# Needs: cmake, ninja or make, a C++ compiler, JDK 21, python3 with numpy, the pinned llama.cpp
# (fetch-llama-cpp.sh, run below), and Gradle-compiled classes:
#   ANDROID_HOME=... ./gradlew -PincludeAndroid=true :app:compileDebugKotlin
#
# Model: by default a small RANDOM-WEIGHT GGUF generated locally (host-smoke/make_synthetic_gguf.py): real format,
# real tokenizer, real inference, meaningless text. It needs no network. For a trained model, pass one:
#   HOST_CHAT_MODEL=/path/to/model.gguf scripts/host-chat-flow.sh
# (host-smoke-test.sh downloads and checks SmolLM2-135M-Instruct Q4_K_M; that file works here too.)
#
#   HOST_CHAT_SCENARIO=longprompt  runs only the >512-token prompt, which aborts the process on a JNI shim built
#                                  before the prompt was decoded in n_batch pieces.
#   HOST_CHAT_SCENARIO=routed      runs the inference-policy checks (this device only, prefer PC with the PC
#                                  unreachable, my PC only) on the real engine. host-routed-flow.sh runs it with networking off.
#   HOST_CHAT_NATIVE_DIR=<dir>     use an already-built libdca_llama_jni.so (and bin/) instead of building.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
root="$(cd "$here/.." && pwd)"
work="${SMOKE_WORKDIR:-$(mktemp -d)}"
scenario="${HOST_CHAT_SCENARIO:-all}"
mkdir -p "$work/variants" "$work/classes"
rm -rf "$work/run"

"$here/scripts/fetch-llama-cpp.sh" >/dev/null
llama="$here/third_party/llama.cpp"

native="${HOST_CHAT_NATIVE_DIR:-$work/host}"
if [ -z "${HOST_CHAT_NATIVE_DIR:-}" ]; then
  java_home="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
  cmake -S "$here/src/main/cpp" -B "$native" -DCMAKE_BUILD_TYPE=Release -DJAVA_HOME="$java_home" >/dev/null
  cmake --build "$native" --target dca_llama_jni -j"$(nproc)" >/dev/null
fi

make_gguf() { python3 "$here/scripts/host-smoke/make_synthetic_gguf.py" "$llama" "$@" >/dev/null; }
model="${HOST_CHAT_MODEL:-$work/synthetic.gguf}"
if [ -z "${HOST_CHAT_MODEL:-}" ]; then make_gguf "$model"; echo "model: synthetic RANDOM-WEIGHT GGUF (replies are noise)"; else echo "model: $model"; fi
make_gguf "$work/variants/split.gguf" --split-count 3
make_gguf "$work/variants/ctx256.gguf" --context 256
make_gguf "$work/variants/notemplate.gguf" --no-template
make_gguf "$work/variants/whole.gguf" --context 1024
head -c 6000000 "$work/variants/whole.gguf" > "$work/variants/truncated.gguf"
rm "$work/variants/whole.gguf"

jar() { find "$HOME/.gradle/caches/modules-2" -name "$1" ! -name '*sources*' | sort -V | tail -1; }
cp="$root/app/build/tmp/kotlin-classes/debug:$here/build/tmp/kotlin-classes/debug"
for d in "$root"/core-*/build/classes/kotlin/main; do cp="$cp:$d"; done
cp="$cp:$(jar 'kotlin-stdlib-2*.jar'):$(jar 'kotlinx-serialization-core-jvm-*.jar'):$(jar 'kotlinx-serialization-json-jvm-*.jar')"
JAVA_TOOL_OPTIONS= javac -cp "$cp" -d "$work/classes" "$here/scripts/host-smoke/ChatFlow.java"

set +e
LD_LIBRARY_PATH="$native:$native/bin" JAVA_TOOL_OPTIONS= \
  java -Djava.library.path="$native" -cp "$work/classes:$cp" ChatFlow "$model" "$work/run" "$scenario" "$work/variants" \
  >"$work/stdout.log" 2>"$work/stderr.log"
status=$?
set -e
grep -E '^(PASS|FAIL|ALL PASS|[0-9]+ FAILED|native|sending)' "$work/stdout.log" || true
if [ "$status" -ne 0 ]; then
  echo "exit status $status (134 = the process aborted); last lines of stderr:"
  tail -4 "$work/stderr.log"
fi
exit "$status"
