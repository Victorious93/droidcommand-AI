#!/usr/bin/env bash
# Fetches the prebuilt sherpa-onnx Android AAR from the project's official GitHub release into libs/
# (gitignored) and refuses to keep it unless its SHA-256 matches the pinned value. A tag/URL alone is not
# trusted. To upgrade, change VERSION and SHA256 together, re-read the release page, and re-verify the
# build. Source: https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.8 (Apache-2.0).
set -euo pipefail

VERSION="1.13.8"
SHA256="633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"
NAME="sherpa-onnx-${VERSION}.aar"
URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v${VERSION}/${NAME}"

libs="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/libs"
dest="$libs/$NAME"

sum_of() { sha256sum "$1" | cut -d' ' -f1; }

if [ -f "$dest" ] && [ "$(sum_of "$dest")" = "$SHA256" ]; then
  echo "$NAME already present and verified"
  exit 0
fi

mkdir -p "$libs"
tmp="$(mktemp "$libs/.download.XXXXXX")"
trap 'rm -f "$tmp"' EXIT
curl --fail --silent --show-error --location --output "$tmp" "$URL"
actual="$(sum_of "$tmp")"
if [ "$actual" != "$SHA256" ]; then
  echo "ERROR: $NAME has SHA-256 $actual, expected $SHA256. Refusing to use it." >&2
  exit 1
fi
mv "$tmp" "$dest"
trap - EXIT
echo "$NAME fetched and verified ($actual)"
