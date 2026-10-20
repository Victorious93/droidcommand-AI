# Victorious Flash integration (`core-flash`)

Victorious Flash (`Victorious93/victorious-flash`) is an **external node**, the same as RootForge. It is
not a companion: there is no DependencyGuard and no shared signing key. The node runs
`victorious-flash-bridge` (crate `crates/flash-bridge`), which owns the canonical protocol. This
module mirrors that protocol by hand.

## Wire contract (protocol major 1)
The envelope is the same as RootForge's: newline-delimited JSON over system OpenSSH with a pinned host key.

```
-> {"protocol_major":1,"request_id":"dca-…","target_node_id":"vf-<32hex>","operation":"…","timeout_ms":N,"parameters":{…}}
<- {"protocol_major":1,"request_id":"dca-…","node_id":"vf-<32hex>","ok":true,"progress":{"phase":"write","done":N,"total":M}}   (0..n, never final)
<- {"protocol_major":1,"request_id":"dca-…","node_id":"vf-<32hex>","ok":true,"result":{…}}  |  "ok":false,"error":{"category","message"}
```

`FlashClient` checks **every** frame, including progress frames, for protocol major, paired `node_id`
and `request_id`. One bad frame fails the call.

| Operation | Tool | Parameters |
|---|---|---|
| `flash.capabilities.get` | `flash_capabilities` | – |
| `flash.catalog.list` | `flash_catalog_list` | – |
| `flash.catalog.update` | `flash_catalog_update` | `url` (https), `sha256` (pinned) |
| `flash.devices.list` | `flash_list_devices` | – |
| `flash.verify` | `flash_verify_image` | `image`, `sha256` |
| `flash.readback.verify` | `flash_readback_verify` | `device`, `sha256`, `length` |
| `flash.image.write` | `flash_write_image` | `image`, `device`, `sha256`, `confirm`, `catalog_id?` |

Error categories: `malformed_request`, `protocol_mismatch`, `node_mismatch`, `unsupported_operation`,
`invalid_parameters`, `safety_refused`, `verify_failed`, `internal`.

## Safety
- Every tool is `SENSITIVE`, `REMOTE_CONTROL` and `requiresConfirmation = true`, so `SecureToolExecutor`
  prompts on **every** call. No approval is remembered. `cli` tests prove that two writes ask twice and
  that a denied call sends nothing.
- `flash_write_image` also needs `confirm` equal to the device name (`sdb` or `/dev/sdb`). It refuses
  locally before any network call, and the node checks again.
- On the node, the write is refused unless the device passes the lsblk removable-disk filter, the image
  hash matches `sha256`, the image fits, and the catalog entry (if given) is `usb-dd` with `iso` or `img`.
  The bytes are written **only** by `pkexec victorious-flash-helper`, which re-validates the target,
  writes, fsyncs and reads back the SHA-256.
- Registry lookup uses the exact node id. There is no default node and no fail-over.

## Setup
1. On the node, run `victorious-flash-bridge node-id`. It prints `vf-<32hex>`.
2. In the node's `authorized_keys`, use
   `command="/usr/bin/victorious-flash-bridge serve",no-pty,no-port-forwarding,no-agent-forwarding,no-X11-forwarding <controller key>`.
3. Remote writes and read-backs need a polkit rule that authorises `com.victorious.flash` for the
   bridge user, because SSH sessions have no polkit agent. The rule is not shipped by default.
4. On the controller:
   ```json
   {"nodes":[{"node_id":"vf-…","host":"flash-box.lan","port":22,"user":"vfbridge",
              "identity_file":"/home/me/.ssh/vf_ed25519","host_key":"ssh-ed25519 AAAA…"}]}
   ```
   `export DROIDCOMMAND_CLI_FLASH_NODES_FILE=/path/to/flash-nodes.json`. If this is unset or the file is
   invalid, no flash tools are registered.

## Code
`core-flash`: `Protocol.kt`, `FlashNodeConfig.kt` (config, parser, `FlashNodeRegistry`), `Transport.kt`
(`ProcessFlashTransport` streams frames; `SshFlashTransport` reuses `core-rootforge`'s `SshCommand`
through the new `SshTarget` overload), `Client.kt`, `Tools.kt`. CLI wiring: `cli/.../FlashWiring.kt`.

## Not built / not verified
- Not run against a real node over SSH or real USB hardware.
- Android `:app` transport: Android has no system `ssh`, so this is JVM/CLI only, like `core-rootforge`.
