#!/usr/bin/env bash
# Host-only retrieval evaluation with a REAL embedding model (not runnable on Android; no device needed).
# Needs: the host shim build (cmake, see below), JDK 21, a nomic-embed-text GGUF, and Gradle-compiled classes:
#   ./gradlew :core-rag:classes :core-llm-local:classes
# Usage: run.sh /path/to/nomic-embed-text-v1.5.Q4_K_M.gguf [path/to/dca-host-build]
# Documents are read from git at a PINNED commit (so the answer fragments in qa.tsv stay valid as docs change).
# Measures retrieval only (is the answer-bearing passage returned); it does not call an LLM.
set -euo pipefail
PIN=12fde171483fd5bcba1cf950efe29bd7200b0de1
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/../../.." && pwd)"
model="${1:?usage: run.sh model.gguf [shim-build-dir]}"
build="${2:-${DCA_HOST_BUILD:-/tmp/dca-host-build}}"
work="$(mktemp -d)"
if [ ! -f "$build/libdca_llama_jni.so" ]; then
  java_home="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
  "$repo/core-llm-local-android/scripts/fetch-llama-cpp.sh"
  cmake -S "$repo/core-llm-local-android/src/main/cpp" -B "$build" -DCMAKE_BUILD_TYPE=Release -DJAVA_HOME="$java_home" >/dev/null
  cmake --build "$build" --target dca_llama_jni -j"$(nproc)" >/dev/null
fi
mkdir -p "$work/corpus"
cut -f1 "$here/qa.tsv" | grep -v '^#' | sort -u | while read -r f; do
  mkdir -p "$work/corpus/$(dirname "$f")"; git -C "$repo" show "$PIN:$f" > "$work/corpus/$f"
done
gc="$HOME/.gradle/caches/modules-2/files-2.1"
jar() { find "$gc" -name "$1" | head -1; }
kc="$(jar kotlin-compiler-embeddable-2.4.10.jar)"; std="$(jar kotlin-stdlib-2.4.10.jar)"
cpk="$kc:$std:$(jar kotlin-script-runtime-2.4.10.jar):$(jar kotlin-reflect-1.6.10.jar):$(jar kotlin-daemon-embeddable-2.4.10.jar):$(jar annotations-13.0.jar):$(jar kotlinx-coroutines-core-jvm-1.8.0.jar):$(find "$HOME/.gradle/wrapper" -name 'trove4j-*.jar' | head -1)"
deps="$repo/core-llm-local/build/classes/kotlin/main:$repo/core-rag/build/classes/kotlin/main:$std"
# The wrapper imports no Android API, so the real file compiles with plain Kotlin (see audit 2026-10-09q).
quiet() { grep -v '^Picked up' "$1" || true; }
if ! java -cp "$cpk" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 21 -cp "$deps" -d "$work/kt" \
  "$repo/core-llm-local-android/src/main/kotlin/ai/droidcommand/llm/local/android/LlamaCppEmbeddingBackend.kt" >"$work/kotlinc.log" 2>&1; then
  quiet "$work/kotlinc.log"; echo "kotlinc failed" >&2; exit 1
fi
if ! javac -cp "$work/kt:$deps" -d "$work/cls" "$here/Eval.java" >"$work/javac.log" 2>&1; then
  quiet "$work/javac.log"; echo "javac failed" >&2; exit 1
fi
rc=0
LD_LIBRARY_PATH="$build:$build/bin" java -Djava.library.path="$build" -cp "$work/cls:$work/kt:$deps" Eval \
  "$model" "$work/corpus" "$here/qa.tsv" "$here/unrelated.tsv" >"$work/eval.log" 2>&1 || rc=$?
quiet "$work/eval.log"
exit "$rc"
