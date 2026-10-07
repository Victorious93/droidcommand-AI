# RootForge integration (`core-rootforge`) — RF-DCA-1

Status: **first slice, passive operations only.** Design source: the 2026-10-07
RootForge × DroidCommand AI × Kai integration blueprint. The matching RootForge-side
document is `docs/DROIDCOMMAND_INTEGRATION.md` in `Victorious93/rootforge-os`, which owns
the canonical protocol; this module mirrors it by hand (see "Protocol sync").

RootForge is an independent product. DCA is an optional controller of it, not a host,
dependency guard or replacement. See `COMPANION_PROTOCOL.md` ("External nodes are not
companions") — RootForge is deliberately exempt from the companion `DependencyGuard`.

## What this module does

| Class | Role |
|---|---|
| `BridgeRequest` / `BridgeResponse` | Kotlin mirror of protocol major 1 (newline-delimited JSON) |
| `RootForgeNodeConfig`, `parseNodeConfigs` | A paired node: exact `node_id`, host, port, user, identity file, **pinned SSH host key** |
| `SshRootForgeTransport` / `SshCommand` | Runs the system OpenSSH client with a pinned per-exchange `known_hosts`, `StrictHostKeyChecking=yes`, no agent/forwarding/password auth, host after `--`, no remote command (the node's key forces the bridge) |
| `ProcessRootForgeTransport` | Same exchange over any local process (contract tests; the SSH engine) |
| `RootForgeClient` | Typed calls; verifies correlation id, protocol major, and that the response's `node_id` is the paired one |
| `RootForgeNodeRegistry` | Exact-id lookup; no default node, no prefix match, no fail-over |
| `rootforge_capabilities`, `rootforge_list_devices` | `Tool`s, `SENSITIVE` / `REMOTE_CONTROL`, so they run through `SecureToolExecutor` policy + approval |

`cli` registers the tools only when `DROIDCOMMAND_CLI_ROOTFORGE_NODES_FILE` points at a
valid node file; unset (the default) means no RootForge tool exists and nothing changes.
`REMOTE_CONTROL` is granted in the CLI's policy only in that case, and each call still
goes through the approval prompt.

```json
{"nodes":[{"node_id":"rf-…32 hex…","host":"rf-pc.lan","port":22,"user":"rfbridge",
           "identity_file":"/path/to/key","host_key":"ssh-ed25519 AAAA…"}]}
```

`host_key` is a single public-key line the owner confirmed out of band
(`rootforge bridge host-key` on the node). A changed key makes the connection fail; it is
never re-learned automatically.

## Honest limits

* **JVM/CLI only.** The transport shells out to the system `ssh`. Android has no such
  binary; an Android client needs another `RootForgeTransport` backed by a vetted SSH
  library. Not built (the `:app` module is still unbuilt here).
* **Passive only.** Capabilities and device enumeration. No builds, jobs, artifacts,
  terminal, backup/restore, flashing — and none will be exposed until the gates listed in
  RootForge's document are repaired.
* **Router not extended.** Exact node selection is done by `RootForgeNodeRegistry`/the
  tools' required `node` input. `ExecutionRouter` selects by target type/capability and was
  left unchanged because this slice has no `ExecutionTarget`; it will need an exact-target
  contract when write/build operations arrive.
* **One SSH connection per request.** No multiplexing, no persistent session.
* `devices.list` can start the node's local adb server (documented on the RootForge side).
* DCA-side approval is necessary but not sufficient: the node enforces its own grants and
  can revoke a controller at any time.

## Protocol sync

Kotlin types here are hand-maintained; `BRIDGE_PROTOCOL_MAJOR` must change together with
RootForge's `PROTOCOL_MAJOR`. The check that they actually agree is
`SshTransportIntegrationTest`, which drives the real RootForge bridge over real OpenSSH
(skipped unless `ROOTFORGE_IT_NODES_FILE` is set):

```
# rootforge-os checkout (installs nothing; creates and removes a throwaway user if run as root)
tests/ssh-roundtrip.sh --keep /tmp/rfit
# droidcommand-AI checkout
ROOTFORGE_IT_NODES_FILE=/tmp/rfit/nodes.json ./gradlew :core-rootforge:test --tests '*SshTransportIntegrationTest*'
# cleanup
kill $(cat /tmp/rfit/sshd.pid); userdel -r <user printed in nodes.json>
```

A vendored schema/fixture set with source version + hash (blueprint §10) is not done yet.
