#!/usr/bin/env bash
# Runs host-chat-flow.sh's "routed" scenario (the inference policy on the REAL JNI shim and llama.cpp) inside a new
# network namespace that has only a loopback interface: no route to a LAN or to the internet exists while it runs.
# That makes "on-device inference with networking disabled" a property of the machine during the run.
#
# Needs root (unshare -n) and everything host-chat-flow.sh needs. Build first, with networking, so nothing has to be
# fetched inside the namespace:
#   SMOKE_WORKDIR=/some/dir HOST_CHAT_SCENARIO=routed scripts/host-chat-flow.sh
# then:
#   SMOKE_WORKDIR=/some/dir HOST_CHAT_NATIVE_DIR=/some/dir/host HOST_CHAT_MODEL=/path/model.gguf scripts/host-routed-flow.sh
#
# This is a Linux host, not Android: it proves the Kotlin and native logic, not a phone's behaviour.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec unshare -n bash -c '
  set -euo pipefail
  # Bring loopback up without iproute2 (SIOCSIFFLAGS on "lo").
  python3 - <<PY
import fcntl, socket, struct
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
flags = struct.unpack("16sh", fcntl.ioctl(s, 0x8913, struct.pack("16sh", b"lo", 0)))[1]
fcntl.ioctl(s, 0x8914, struct.pack("16sh", b"lo", flags | 1))
PY
  echo "interfaces in this namespace: $(python3 -c "import socket; print(\" \".join(n for _, n in socket.if_nameindex()))")"
  if python3 -c "import socket; socket.create_connection((\"1.1.1.1\", 443), 3)" 2>/dev/null; then
    echo "ERROR: the internet is reachable from inside the namespace" >&2; exit 2
  fi
  echo "outside connectivity: none (connect to 1.1.1.1:443 failed, as intended)"
  HOST_CHAT_SCENARIO=routed "'"$here"'/host-chat-flow.sh"
'
