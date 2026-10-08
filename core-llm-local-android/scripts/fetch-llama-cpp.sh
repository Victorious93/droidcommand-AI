#!/usr/bin/env bash
# Fetches llama.cpp and the Khronos header-only dependencies its Vulkan backend needs, each at a
# pinned tag, into third_party/ (gitignored). Every checkout must resolve to exactly the pinned
# commit or it is deleted and the script fails: a tag name alone is not trusted (tags can be
# moved, a commit hash cannot). To upgrade, change BOTH tag and commit and re-verify the build.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/third_party"

head_of() { git -C "$1" rev-parse HEAD 2>/dev/null || true; }

# fetch_pinned NAME URL TAG COMMIT
fetch_pinned() {
  local name="$1" url="$2" tag="$3" commit="$4" dest="$root/$1"
  if [ -d "$dest/.git" ] && [ "$(head_of "$dest")" = "$commit" ]; then
    echo "$name $tag already present at pinned commit"
    return 0
  fi
  rm -rf "$dest"
  mkdir -p "$root"
  git -c advice.detachedHead=false clone --quiet --depth 1 --branch "$tag" "$url" "$dest"
  local actual
  actual="$(head_of "$dest")"
  if [ "$actual" != "$commit" ]; then
    rm -rf "$dest"
    echo "ERROR: $name tag $tag resolved to $actual, expected $commit. Refusing to use it." >&2
    return 1
  fi
  echo "$name $tag fetched and verified ($actual)"
}

fetch_pinned llama.cpp https://github.com/ggml-org/llama.cpp b11484 5de733437bc5f6d6b2714b39c4023fd3d3b16698
fetch_pinned Vulkan-Headers https://github.com/KhronosGroup/Vulkan-Headers vulkan-sdk-1.4.363.0 6802bb4733b63ed5efd3adb308a6c885ef180ea1
fetch_pinned SPIRV-Headers https://github.com/KhronosGroup/SPIRV-Headers vulkan-sdk-1.4.363.0 496543121ce6419f23d6fa5d7194ba66c36212d2
