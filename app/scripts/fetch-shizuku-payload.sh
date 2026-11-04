#!/usr/bin/env bash
# Fetches the official Shizuku release APK (Apache-2.0) from GitHub Releases,
# refuses it unless the SHA-256 matches the pin, and copies LICENSE/NOTICE plus
# the APK into the generated assets used by :app.
#
# Source: https://github.com/RikkaApps/Shizuku/releases/tag/v13.6.0
set -euo pipefail

VERSION="13.6.0"
ASSET="shizuku-v13.6.0.r1086.2650830c-release.apk"
SHA256="6e273ab0e991c4e79bc8b1bbb9b9dd739ccac1a8712a541a214078886b7b790f"
URL="https://github.com/RikkaApps/Shizuku/releases/download/v${VERSION}/${ASSET}"

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
app_dir="$(cd "$script_dir/.." && pwd)"
repo_dir="$(cd "$app_dir/.." && pwd)"
cache="$app_dir/build/shizuku-payload"
dest_dir="$app_dir/build/generated/shizuku/assets/shizuku"
jni_dir="$app_dir/build/generated/shizuku/jniLibs"
cache_apk="$cache/$ASSET"
dest_apk="$dest_dir/shizuku-server.apk"

sum_of() { sha256sum "$1" | cut -d' ' -f1; }

mkdir -p "$cache" "$dest_dir" "$jni_dir"

if [ ! -f "$cache_apk" ] || [ "$(sum_of "$cache_apk")" != "$SHA256" ]; then
  tmp="$(mktemp "$cache/.download.XXXXXX")"
  trap 'rm -f "$tmp"' EXIT
  curl --fail --silent --show-error --location --output "$tmp" "$URL"
  actual="$(sum_of "$tmp")"
  if [ "$actual" != "$SHA256" ]; then
    echo "ERROR: $ASSET has SHA-256 $actual, expected $SHA256. Refusing to use it." >&2
    exit 1
  fi
  mv "$tmp" "$cache_apk"
  trap - EXIT
fi

cp -f "$cache_apk" "$dest_apk"
cp -f "$repo_dir/third_party/shizuku/LICENSE" "$dest_dir/LICENSE"
cp -f "$repo_dir/third_party/shizuku/NOTICE" "$dest_dir/NOTICE"

python3 - "$cache_apk" "$jni_dir" <<'PY'
import sys, zipfile
from pathlib import Path
apk, dest = Path(sys.argv[1]), Path(sys.argv[2])
wanted = {"libadb.so", "libshizuku.so"}
with zipfile.ZipFile(apk) as z:
    for name in z.namelist():
        parts = name.split("/")
        if len(parts) == 3 and parts[0] == "lib" and parts[2] in wanted:
            out = dest / parts[1] / parts[2]
            out.parent.mkdir(parents=True, exist_ok=True)
            out.write_bytes(z.read(name))
            print(f"extracted {name} -> {out}")
PY

echo "Shizuku v${VERSION} payload verified ($(sum_of "$dest_apk"))"
