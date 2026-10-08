#!/usr/bin/env bash
# Runs REAL inference through the real JNI shim and LlamaCppBackend on a Linux x86_64 HOST
# (not Android). Needs: cmake, a C++ compiler, a JDK 21, the pinned llama.cpp (run
# fetch-llama-cpp.sh), and Gradle-compiled classes:
#   ANDROID_HOME=... ./gradlew :core-llm-local:classes :core-agent:classes :core-llm-local-android:compileDebugKotlin
# Downloads a ~105 MB Apache-2.0 model (SmolLM2-135M-Instruct Q4_K_M) into a scratch dir and
# checks its SHA-256 before use. This proves the shim and backend logic, NOT Android behavior.
set -euo pipefail

MODEL_URL="https://huggingface.co/unsloth/SmolLM2-135M-Instruct-GGUF/resolve/main/SmolLM2-135M-Instruct-Q4_K_M.gguf"
MODEL_SHA256="ed5fa30c487b282ec156c29062f1222e5c20875a944ac98289dbd242e947f747"

here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
root="$(cd "$here/.." && pwd)"
work="${SMOKE_WORKDIR:-$(mktemp -d)}"
mkdir -p "$work/model" "$work/host" "$work/classes"

"$here/scripts/fetch-llama-cpp.sh"

model="$work/model/model.gguf"
if [ ! -f "$model" ] || [ "$(sha256sum "$model" | cut -d' ' -f1)" != "$MODEL_SHA256" ]; then
  curl -sSL -o "$model" "$MODEL_URL"
fi
[ "$(sha256sum "$model" | cut -d' ' -f1)" = "$MODEL_SHA256" ] || { echo "model SHA-256 mismatch; refusing to run" >&2; exit 1; }

java_home="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
cmake -S "$here/src/main/cpp" -B "$work/host" -DCMAKE_BUILD_TYPE=Release -DJAVA_HOME="$java_home" >/dev/null
cmake --build "$work/host" --target dca_llama_jni -j"$(nproc)" >/dev/null

kotlin_std="$(find "$HOME/.gradle" -name 'kotlin-stdlib-*.jar' ! -name '*sources*' | head -1)"
cp="$here/build/tmp/kotlin-classes/debug:$root/core-llm-local/build/classes/kotlin/main:$root/core-agent/build/classes/kotlin/main:$kotlin_std"
javac -cp "$cp" -d "$work/classes" "$here/scripts/host-smoke/Smoke.java"

LD_LIBRARY_PATH="$work/host:$work/host/bin" \
  java -Djava.library.path="$work/host" -cp "$work/classes:$cp" Smoke "$model" 2>&1 | grep -E '^(loaded|OUTPUT|pieces|early|MULTIBYTE|overflow|unloaded)'
