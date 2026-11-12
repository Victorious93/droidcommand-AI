#!/usr/bin/env bash
# Runs on-device tool calling (LocalToolProtocol's grammar, LocalLlmProvider, ChatSession.sendLocal, LlamaCppBackend)
# against the REAL JNI shim and llama.cpp on a Linux x86_64 HOST. See host-smoke/ToolFlow.java for what it checks.
# It proves the grammar is accepted and enforced by llama.cpp and that the Kotlin side handles real sampled output.
# It does NOT prove Android behavior, and with the default model it does NOT prove that a model chooses tools well.
#
# Needs: cmake, a C++ compiler, JDK 21, python3 with numpy, the pinned llama.cpp (fetched below), and Gradle-compiled
# classes:  ANDROID_HOME=... ./gradlew -PincludeAndroid=true :app:compileDebugKotlin
#
# Model: by default two small RANDOM-WEIGHT GGUFs generated locally (host-smoke/make_synthetic_gguf.py), one carrying
# llama.cpp's copy of the Qwen 2.5 chat template (tool-capable) and one plain ChatML. No network. For a trained model:
#   HOST_TOOL_MODEL=/path/to/qwen2.5-0.5b-instruct-q4_k_m.gguf scripts/host-tool-flow.sh
#   HOST_CHAT_NATIVE_DIR=<dir>   use an already-built libdca_llama_jni.so (and bin/) instead of building.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
root="$(cd "$here/.." && pwd)"
work="${SMOKE_WORKDIR:-$(mktemp -d)}"
mkdir -p "$work/models" "$work/classes"

"$here/scripts/fetch-llama-cpp.sh" >/dev/null
llama="$here/third_party/llama.cpp"

native="${HOST_CHAT_NATIVE_DIR:-$work/host}"
if [ -z "${HOST_CHAT_NATIVE_DIR:-}" ]; then
  java_home="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
  cmake -S "$here/src/main/cpp" -B "$native" -DCMAKE_BUILD_TYPE=Release -DJAVA_HOME="$java_home" >/dev/null
  cmake --build "$native" --target dca_llama_jni -j"$(nproc)" >/dev/null
fi

make_gguf() { python3 "$here/scripts/host-smoke/make_synthetic_gguf.py" "$llama" "$@" >/dev/null; }
tool_model="${HOST_TOOL_MODEL:-$work/models/tool-template.gguf}"
if [ -z "${HOST_TOOL_MODEL:-}" ]; then
  make_gguf "$tool_model" --tool-template
  echo "model: synthetic RANDOM-WEIGHT GGUF with the Qwen 2.5 chat template (output is noise held to the grammar)"
else
  echo "model: $tool_model"
fi
make_gguf "$work/models/plain-template.gguf"

jar() { find "$HOME/.gradle/caches/modules-2" -name "$1" ! -name '*sources*' | sort -V | tail -1; }
cp="$here/build/tmp/kotlin-classes/debug"
for d in "$root"/core-*/build/classes/kotlin/main; do cp="$cp:$d"; done
cp="$cp:$(jar 'kotlin-stdlib-2*.jar'):$(jar 'kotlinx-serialization-core-jvm-*.jar'):$(jar 'kotlinx-serialization-json-jvm-*.jar')"
JAVA_TOOL_OPTIONS= javac -cp "$cp" -d "$work/classes" "$here/scripts/host-smoke/ToolFlow.java"

set +e
LD_LIBRARY_PATH="$native:$native/bin" JAVA_TOOL_OPTIONS= \
  java -Djava.library.path="$native" -cp "$work/classes:$cp" ToolFlow "$tool_model" "$work/models/plain-template.gguf" \
  >"$work/stdout.log" 2>"$work/stderr.log"
status=$?
set -e
grep -E '^(PASS|FAIL|ALL PASS|[0-9]+ FAILED)' "$work/stdout.log" || true
if [ "$status" -ne 0 ]; then
  echo "exit status $status (134 = the process aborted); last lines of stderr:"
  tail -6 "$work/stderr.log"
fi
exit "$status"
