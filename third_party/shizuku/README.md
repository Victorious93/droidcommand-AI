# Embedded Shizuku payload (Apache-2.0)

DroidCommand AI embeds the official Shizuku server and starter so the
separate Shizuku manager app is not required.

- Upstream: https://github.com/RikkaApps/Shizuku
- License: Apache License 2.0 (`LICENSE` in this directory)
- Attribution: `NOTICE` in this directory
- Pin: v13.6.0 (`shizuku-v13.6.0.r1086.2650830c-release.apk`)
- SHA-256: `6e273ab0e991c4e79bc8b1bbb9b9dd739ccac1a8712a541a214078886b7b790f`
- Official release: https://github.com/RikkaApps/Shizuku/releases/tag/v13.6.0

`app/scripts/fetch-shizuku-payload.sh` downloads that exact asset from
GitHub Releases, refuses it if the digest does not match, and copies it
into the app's generated assets as `shizuku/shizuku-server.apk`.

Do not replace the pin without re-reading the upstream LICENSE and
recomputing the digest yourself. Never commit ADB keys.
