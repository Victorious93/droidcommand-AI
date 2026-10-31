#!/usr/bin/env bash
# Shared by android-checks / android-apk / android-emulator.
# sdkmanager sometimes dies on a truncated NDK zip
# ("Error on ZipFile unknown archive"). Retry that class of
# download error; a real package-name mistake still fails fast.
set -euo pipefail

yes | sdkmanager --licenses > /dev/null || true

delay=15
for attempt in 1 2 3 4 5; do
  echo "::group::sdkmanager attempt #${attempt}"
  log="$(mktemp)"
  set +e
  sdkmanager \
    "platform-tools" \
    "platforms;android-36" \
    "build-tools;36.0.0" \
    "ndk;28.2.13676358" \
    "cmake;3.22.1" 2>&1 | tee "$log"
  status=${PIPESTATUS[0]}
  set -e
  echo "::endgroup::"
  if [ "${status}" -eq 0 ]; then
    rm -f "$log"
    exit 0
  fi
  if ! grep -qE \
    'Error on ZipFile|unknown archive|Connection reset|SocketTimeout|UnknownHost|timed out|503 |502 |Connection refused' \
    "$log"; then
    echo "Non-transient sdkmanager failure; not retrying"
    rm -f "$log"
    exit 1
  fi
  rm -f "$log"
  if [ "${attempt}" -eq 5 ]; then
    echo "sdkmanager failed after all retries"
    exit 1
  fi
  echo "attempt #${attempt} failed with a transient SDK download error; sleeping ${delay}s"
  sleep "${delay}"
  delay=$((delay * 2))
done
exit 1
