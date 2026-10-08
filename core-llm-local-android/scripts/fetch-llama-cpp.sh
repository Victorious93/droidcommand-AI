#!/usr/bin/env bash
# Fetches llama.cpp at a pinned tag into third_party/llama.cpp (gitignored) and refuses to
# proceed unless HEAD is exactly the pinned commit. A tag name alone is not trusted: tags can be
# moved, the commit hash cannot. To upgrade, change BOTH values below and re-verify the build.
set -euo pipefail

LLAMA_CPP_TAG="b11484"
LLAMA_CPP_COMMIT="5de733437bc5f6d6b2714b39c4023fd3d3b16698"
LLAMA_CPP_URL="https://github.com/ggml-org/llama.cpp"

dest="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/third_party/llama.cpp"

head_of() { git -C "$1" rev-parse HEAD 2>/dev/null || true; }

if [ -d "$dest/.git" ] && [ "$(head_of "$dest")" = "$LLAMA_CPP_COMMIT" ]; then
  echo "llama.cpp $LLAMA_CPP_TAG already present at pinned commit"
  exit 0
fi

rm -rf "$dest"
mkdir -p "$(dirname "$dest")"
git -c advice.detachedHead=false clone --quiet --depth 1 --branch "$LLAMA_CPP_TAG" "$LLAMA_CPP_URL" "$dest"
actual="$(head_of "$dest")"
if [ "$actual" != "$LLAMA_CPP_COMMIT" ]; then
  rm -rf "$dest"
  echo "ERROR: tag $LLAMA_CPP_TAG resolved to $actual, expected $LLAMA_CPP_COMMIT. Refusing to use it." >&2
  exit 1
fi
echo "llama.cpp $LLAMA_CPP_TAG fetched and verified ($actual)"
