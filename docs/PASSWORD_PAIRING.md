# Password pairing (protocol v1)

A user-set pairing password can link a fleet app whose catalog pin is
missing or does not match. It complements cert pins. It does not replace
them, does not turn external control on, and does not let the four
excluded apps in.

Code: `core-security.PasswordPairingService`. Android store:
`core-config.VaultPasswordPairingStore` inside `EncryptedSecretsVault`.
Settings: Password pairing section. Fleet: Password button on an
installed EXTERNAL_NODE. Inbound: `COMPANION_ASSIST` `task=pair` or an
assist request that carries a `pairing` object.

[Certain] JVM-tested. [Certain] no device or emulator ran this path.

## What it is for

Inbound pending-refused callers (VictorSMS, VictorCam, VictorTrade,
VictorTaskbar, VictorMango, VictorSpoof, VictorGPS, RootForge release
`ai.rootforge.os`) and outbound catalog-pin mismatches can be linked
after the owner types the pairing password and taps Allow.

Later reconnects accept **only** that package + the observed cert bound
at link time (TOFU). A changed cert is refused. Revoke the link, then
link again, if the owner really re-signed the APK.

## What it is not

- External control stays **OFF** by default. A password link is not a
  remote-command grant.
- Destructive inbound assist stays `confirmation_required` (the Allow
  tap). It still does not run tools.
- The four excluded apps (VictoryPed, VictorLink64, MatchVictorious,
  VictorOps Zombies) can **never** be linked. A tampered snapshot that
  names one of them is dropped on decode.
- HackerAI (`ai.hackerai.companion`), Pentest-Swarm
  (`ai.pentestswarm.companion`), and EDL-Commander
  (`ai.edlcommander.companion`) use Android same-signer signature
  permissions. A password link **does not** bypass that OS check. Those
  peers still need a matching debug/release signing key on the hub, or
  a change in their own repo. Documented here so a hub-only password
  does not get mistaken for an AIDL fix.

## Defaults and storage

- Pairing is **OFF** until the owner turns it on in Settings.
- The password is stored as PBKDF2-HMAC-SHA256 (210 000 iterations,
  16-byte salt, 32-byte key). Never plaintext. Never logged.
- Snapshot id in the vault: `password-pairing.snapshot`.
- Five failures on one package lock that package for 15 minutes.
- Minimum password length: 8 characters.
- Links are revocable per package. Clearing the password drops every
  link and turns pairing OFF.

## Wire protocol (HMAC-SHA256 challenge-response)

Chosen instead of SPAKE2 so this repo does not grow a new crypto
dependency. The password itself never crosses the wire.

HMAC message:

```
dca-password-pairing-v1|{packageName}|{certCanonical}|{nonceHex}
```

`certCanonical` is the SHA-256 of the caller's signing cert as 64
lowercase hex characters (colons stripped). `nonceHex` is 32 random
bytes, hex-encoded. MAC is HMAC-SHA256 of that message with the
derived PBKDF2 key.

### COMPANION_ASSIST

Optional `pairing` object on the existing request JSON:

```json
{
  "v": 1,
  "task": "pair",
  "pairing": {
    "protocol": "password-pairing",
    "v": 1,
    "needChallenge": true
  }
}
```

Hub reply (`RESULT_OK`):

```json
{
  "v": 1,
  "ok": true,
  "protocol": "password-pairing",
  "challengeId": "...",
  "nonce": "..."
}
```

Peer proof (after the owner taps Allow):

```json
{
  "v": 1,
  "task": "pair",
  "pairing": {
    "challengeId": "...",
    "nonce": "...",
    "mac": "..."
  }
}
```

`task` may also stay `assist`. A `pairing` proof is applied before the
model runs. `task=pair` never calls the model.

Challenges expire after 5 minutes. Issuing a challenge does not need
Allow (the nonce is not secret). Completing a handshake does.

### Messenger

The same HMAC fields can ride a later EXTERNAL_NODE handshake. This
repo's first slice is hub-local: the owner types the password on the
Fleet screen after seeing the observed cert. Peers that do not yet
speak `pairing` can still be linked that way.

## Hub-local confirm

`PasswordPairingService.confirmWithPassword` is the path when the peer
has no HMAC client yet. The owner sees package + cert, types the
password, taps Allow. Same TOFU bind as a successful HMAC.

## Trust order

1. Excluded package → refuse (no password path).
2. Catalog pin match → allow (password not required).
3. Already-linked package + unchanged TOFU cert → allow.
4. Otherwise pending-refused / pin mismatch stay refused unless pairing
   is ON, a password is set, and the owner confirms.

`TrustPipeline` uses step 3 as an override when a catalogued pin does
not match the source cert. `externalControlEnabled` is still checked
after pairing and stays OFF by default.

## Peer work (not in this PR)

HackerAI / Pentest-Swarm / EDL still need same-signer (or a peer-side
change) before AIDL bind works. Inbound SMS/Cam/Trade/Taskbar/Mango/
Spoof/GPS/RootForge-release can send `task=pair` once those apps grow
a client. Until then, Settings → Link installed package and Fleet →
Password are the hub-local links.
