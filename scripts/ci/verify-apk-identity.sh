#!/usr/bin/env bash
# Verifies the identity of an APK before it is published or handed over.
#   verify-apk-identity.sh <apk> [expected-tag]      e.g. verify-apk-identity.sh app-arm64-debug.apk v0.1.7
# Checks: package id, versionName == tag (when a tag is given), versionCode == major*10000+minor*100+patch (and > 1),
# the signer is DroidCommand's pinned debug certificate (docs/SIGNING.md) and the only signer, native ABIs, and that
# every native library is 16 KB page aligned (Android 15+ devices may use 16 KB pages; a 4 KB emulator cannot show this).
# Prints one `key: value` line per fact, exits 1 on any mismatch.
set -euo pipefail
APK="${1:?apk}"; TAG="${2:-}"
SDK="${ANDROID_HOME:-/opt/android-sdk}"
BT="$(ls -d "$SDK"/build-tools/*/ | sort -V | tail -1)"
EXPECTED_PACKAGE=ai.droidcommand.app
# docs/SIGNING.md: DroidCommand's own debug identity (not a release key, not a fleet key).
EXPECTED_CERT=880051a6d95d38dd8d81e1931e1705480387d593c30943539968e6ef11a0c84d
fail=0
bad() { echo "MISMATCH: $*" >&2; fail=1; }

badging="$("$BT"aapt2 dump badging "$APK" 2>/dev/null)"
pkg="$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<<"$badging")"
vcode="$(sed -n "s/^package:.* versionCode='\([^']*\)'.*/\1/p" <<<"$badging")"
vname="$(sed -n "s/^package:.* versionName='\([^']*\)'.*/\1/p" <<<"$badging")"
abis="$(sed -n "s/^native-code: //p" <<<"$badging" | tr -d "'")"
echo "package: $pkg"; echo "versionName: $vname"; echo "versionCode: $vcode"; echo "native-code: $abis"
[[ "$pkg" == "$EXPECTED_PACKAGE" ]] || bad "package is '$pkg', expected $EXPECTED_PACKAGE"
[[ "$vcode" =~ ^[0-9]+$ ]] && (( vcode > 1 )) || bad "versionCode '$vcode' must be a number greater than 1 (every release up to v0.1.6 shipped 1)"
if [[ -n "$TAG" ]]; then
  [[ "$TAG" == "v$vname" ]] || bad "tag '$TAG' does not match versionName '$vname'"
fi
if [[ "$vname" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
  want=$(( BASH_REMATCH[1] * 10000 + BASH_REMATCH[2] * 100 + BASH_REMATCH[3] ))
  [[ "$vcode" == "$want" ]] || bad "versionCode $vcode does not follow major*10000+minor*100+patch ($want for $vname)"
else
  bad "versionName '$vname' is not x.y.z"
fi

certs="$("$BT"apksigner verify --print-certs "$APK" 2>&1 | grep -v '^Picked up' || true)"
signers="$(grep -c 'certificate SHA-256 digest' <<<"$certs" || true)"
cert="$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' <<<"$certs")"
echo "signer-sha256: $cert (signers: $signers)"
[[ "$signers" == "1" ]] || bad "expected exactly one signer, saw $signers"
[[ "$cert" == "$EXPECTED_CERT" ]] || bad "signing certificate $cert is not the pinned DroidCommand debug certificate"

if READELF="$(ls "$SDK"/ndk/*/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf 2>/dev/null | head -1)" && [[ -n "$READELF" ]]; then
  tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
  unzip -q -o "$APK" 'lib/*' -d "$tmp" 2>/dev/null || true
  while IFS= read -r so; do
    align="$("$READELF" -lW "$so" | awk '/LOAD/ {print $NF}' | sort -u | tr '\n' ' ')"
    for a in $align; do [[ "$a" == "0x4000" || "$a" == "0x10000" ]] || bad "$(basename "$so") has LOAD alignment $a (needs 0x4000 or more for 16 KB pages)"; done
  done < <(find "$tmp" -name '*.so')
  echo "native-alignment: checked $(find "$tmp" -name '*.so' | wc -l) libraries"
fi
exit $fail
