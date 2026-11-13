#!/usr/bin/env bash
# usage: build.sh <mode> <authority> <package> <outdir>   -> <outdir>/<package>-<mode>.apk (debug-signed with a throwaway key)
set -euo pipefail
mode="$1"; authority="$2"; pkg="$3"; out="$4"
SDK="${ANDROID_HOME:-/opt/android-sdk}"; BT="$SDK/build-tools/36.0.0"; JAR="$SDK/platforms/android-36/android.jar"
here="$(cd "$(dirname "$0")" && pwd)"; work="$(mktemp -d)"; mkdir -p "$out"
cat > "$work/AndroidManifest.xml" <<M
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$pkg">
  <uses-sdk android:minSdkVersion="28" android:targetSdkVersion="28"/>
  <application android:label="fake $mode">
    <provider android:name="fake.fleet.FakeProvider" android:authorities="$authority" android:exported="true">
      <meta-data android:name="mode" android:value="$mode"/>
    </provider>
  </application>
</manifest>
M
run() { "$@" > "$work/step.log" 2>&1 || { cat "$work/step.log" >&2; exit 1; }; }
mkdir -p "$work/classes"
run javac -nowarn --release 8 -cp "$JAR" -d "$work/classes" "$here/src/FakeProvider.java"
run "$BT/d8" --min-api 28 --lib "$JAR" --output "$work" "$work/classes/fake/fleet/FakeProvider.class"
run "$BT/aapt2" link -o "$work/base.apk" -I "$JAR" --manifest "$work/AndroidManifest.xml"
(cd "$work" && zip -q -j base.apk classes.dex)
key="$out/.throwaway.keystore"
[ -f "$key" ] || keytool -genkeypair -keystore "$key" -storepass android -keypass android -alias k -keyalg RSA -keysize 2048 -validity 3650 -dname "CN=qa fixture" 2>/dev/null
"$BT/zipalign" -f 4 "$work/base.apk" "$work/aligned.apk"
run "$BT/apksigner" sign --ks "$key" --ks-pass pass:android --key-pass pass:android --out "$out/$pkg-$mode.apk" "$work/aligned.apk"
echo "$out/$pkg-$mode.apk"
