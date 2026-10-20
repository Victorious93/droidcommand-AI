package ai.droidcommand.remotecontrol

/**
 * In-app Help copy. Same facts as `docs/MANUAL.md`. Not a substitute for
 * the spec (`docs/specs/REMOTE_CONTROL.md`).
 */
object RemoteControlManual {
    const val TITLE = "DroidCommand AI — user manual"

    val BODY: String = """
        DroidCommand AI is the controller. It authorizes remote actions on
        fleet apps. Live wipe, power-off, lock, flash, bootloader, and
        RustDesk are not implemented in this skeleton.

        TRUST MODEL
        An inbound command is an UNTRUSTED REQUEST. A VictorSMS whitelist
        plus code word is not authorization — it only delivers the request.
        The controller is the sole authorizer. It re-applies, in order:
        (1) caller-id = package+cert pairing of the source node;
        (2) external-control switch, OFF by default, on both node ops and
        the controller inbound surface;
        (3) envelope/replay check (nested auth object);
        (4) the controller's own policy gates.
        Destructive ops always stay behind device-admin/root/Shizuku and
        explicit approval. A remote trigger never auto-approves and never
        auto-sends on behalf of a remote caller.

        Triggers (four kinds)
        • SMS: VictorSMS (com.victorious.sms) on the paired device.
          VictorSMS owns whitelist + code word. After those gates it
          forwards a ratified envelope. That is delivery, not authorization.
        • Voice: an exact phrase you configure here (local, not inbound).
        • Schedule: stored expression only; live scheduler is a stub.
        • Manual: Run on a mapping or macro (still asks first).

        Ratified envelope
        Anti-replay fields are nested under auth (not top-level):
        auth.nonce, auth.issuedAt, auth.ttlMs.
        Plus requestId (BridgeRequest request_id), targetNodeRef, actionRef,
        args, destructive (advisory only). Authorization is package+cert
        (sendingUid), never a payload field. trigger extras are
        informational only.

        Generic command surface
        capabilities.get advertises protocol minor 1 and per-op descriptors
        (name, kind read|action|destructive, remoteAllowed, requiresConfirm).
        macro.step { op, params } and macro.run { steps, dryRun } run only
        advertised ops, sequentially. abort = stop-on-first-error + report
        partial; no rollback. dryRun validates params + gates without
        executing.

        Destructive actions
        WIPE, POWER_OFF, LOCK, flash, and bootloader always prompt on this
        device (SecureToolExecutor → ApprovalPrompt). A forged
        destructive:false does not bypass that. YOLO may waive
        non-destructive remote commands the same way it waives shell; it
        cannot waive destructive remote actions or Kali/pentest
        authorization (the phrase "I confirm these targets are authorized").

        Shizuku
        Privileged ADB-shell ops never auto-run. Missing Shizuku degrades
        to unprivileged use; it does not skip confirmation.

        Fleet control
        Tools → Fleet lists HackerAI plus eleven KnownFleet members
        (including VictorSMS and VictorSpoof). None is LIVE. Pairing is
        package + observed cert. EXTERNAL_NODE members never get
        DependencyGuard or a shared signing key.

        VictorSpoof
        Package com.victorious.spoof, EXTERNAL_NODE, novelty/prank
        caller-ID. Isolated from wipe/power-off/lock/flash/bootloader.
        Certs are not pinned yet (fail-open). TODO: pin later.

        Status tray
        Consumes and publishes last health rows per node (mesh / VPN /
        browser / SMS / node health), shared with VictorLaunch. Exact
        schema/transport is still pending. No live bind.

        Local backup
        Settings can export versioned JSON of this controller's macros,
        mappings, and device pairings (plus targets and status tray) to a
        file on this device. API keys and VictorSMS secrets are not
        included. Unified fleet backup schema is still pending.

        Controller inbound (named, not exported here)
        Action ai.droidcommand.app.action.BRIDGE, op remote.command.
    """.trimIndent()
}
