# Connect a device (phone ↔ computer or phone ↔ phone)

Link this phone to a computer or another phone on the same Wi-Fi. Both sides
type the same one-time password, then check that a short code matches.

[Certain] JVM-tested. [Certain] no real phone or computer pair ran this path
in the session that added it.

## What it is

- **Where:** Fleet → **Connect a computer or another phone**.
- **Choices:** a computer, or another phone that also runs this app.
- **How they find each other:** type `host:port`, or paste the invite line
  shown on the waiting device. The invite is meant to be turned into a QR
  code with any QR app; this slice prints it as text. mDNS is optional and
  is **not** shipped.
- **How they prove it is you:** a one-time password you set or the app
  makes. The password is stretched (PBKDF2, same numbers as PR #262) and
  used in SPAKE2. It is never sent as readable text.
- **Check:** both screens show the same `123-456` style code. Tap Yes only
  if they match.
- **After that:** this phone remembers the other device's key (trust on
  first use). Forget it to revoke that one device.

## Defaults

- Waiting is **off**. The listener runs only while that screen is open, or
  if you turn on **Keep waiting after I leave this screen**.
- Remote control stays **off**. Pairing is not a remote-command grant.
- After pairing, only: check connection (ping), a short status, or send a
  note / small text file into Chat. Anything that can change the phone
  still goes through Remote control in Settings and the existing confirm
  screens.
- Excluded apps and inbound policies are unchanged. No keys are committed.

## Overlap with open PRs

- **PR #262** (`cursor/password-pairing-b61a`) is a **different** password
  handshake: HMAC over a fleet app package + signing cert, so a pending
  cert can still be linked. This feature does **not** depend on that
  branch. It copies the lockout (5 failures / 15 minutes), PBKDF2 numbers,
  TOFU + per-device revoke, and OFF-by-default rules. The two stores are
  separate (`password-pairing.snapshot` vs `device-connect.snapshot`).
  Whichever merges second should keep both; do not fold them into one
  service.
- **PR #264** (Advanced tab + APK chip) and **PR #259** (Kai
  Foundation) are now on `main`. This branch rebased onto that.
  Connect stays on **Fleet**. The theme picker stays in
  **Settings → Look**. Kai capability switches stay on Advanced,
  off and not flipable. Do not move Connect or Look under Advanced.

## Computer companion

`tools/pc-companion` is a small Kotlin/JVM CLI that uses the same module
(`core-device-connect`):

```
./gradlew :pc-companion:run --args="listen"
./gradlew :pc-companion:run --args="join 192.168.1.8:17890 MyPassword"
./gradlew :pc-companion:run --args="list"
```

After pairing it only pings and reads status. It cannot run commands on
the phone. Pairing state is `~/.droidcommand/device-connect.json` (owner
read/write only when the filesystem allows).

## Protocol (v1)

1. One side waits on TCP port 17890 (or the bound port).
2. The joiner sends a SPAKE2 share, its device id, and its long-term
   public key. The waiter answers with its share.
3. Both compute the same secret from the password. They exchange a MAC
   over the transcript so a wrong password fails here.
4. Both show a short verification code derived from the secret.
5. After both confirm the codes match, each signs the transcript with
   its device key. The first time, that public key is stored (TOFU).
6. Session messages use the existing AES-256-GCM `SecureChannel` from
   `core-remote` (Noise-style transport after the PAKE).

Wrong passwords are counted per remote address. Five failures lock that
address for 15 minutes.

## What this does not do

- No camera QR scanner.
- No mDNS advertisement.
- No remote control, wipe, shell, or fleet command from a paired PC.
- No live phone or emulator pairing was run when this was written.
