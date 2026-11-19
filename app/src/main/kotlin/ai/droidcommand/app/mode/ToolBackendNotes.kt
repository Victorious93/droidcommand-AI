package ai.droidcommand.app.mode

/**
 * Honest one-liners for the tools [ai.droidcommand.app.di.AppModule] registers
 * against Null* or companion-not-connected backends. Pilot shows this under the
 * selected tool so a run is not a surprise refusal.
 */
object ToolBackendNotes {
    fun forName(name: String): String? = when (name) {
        "run_root_command" ->
            "No rooted device is connected (NullRootExecutor). A run is refused."
        "run_termux_command" ->
            "VictorSuite RUN_COMMAND uses RUN_COMMAND_PENDING_INTENT (ClientBackedTermuxExecutor). " +
                "On-device round-trip is UNVERIFIED; allow-external-apps=true and the RUN_COMMAND permission are required."
        "run_shizuku_command", "shizuku_read_device_state", "shizuku_pm_grant" ->
            "Shizuku/Sui UserService (BoundPrivilegedShell), including the built-in server on Advanced. " +
                "Each run still asks for confirmation. Without Shizuku the rest of the app still works. " +
                "On-device round-trip is UNVERIFIED."
        "run_metasploit_module" ->
            "Runs against whichever backend is selected in Security tool settings: VictorSuite Kali via " +
                "RUN_COMMAND (TermuxMetasploitExecutor, on-device msfconsole round-trip UNVERIFIED), or a " +
                "configured VictorLinux security backend over its RPC-backed Linux-backend client " +
                "(LinuxBackendMetasploitExecutor — real end-to-end job lifecycle verified against that " +
                "backend's own real msfrpcd; this phone-side client itself has not yet been run against a " +
                "live backend instance). No backend selected falls back to NullMetasploitExecutor."
        "run_kali_authorized_scan" ->
            "Local nmap+msfconsole playbook inside VictorSuite Kali. Safety gates are mandatory. " +
                "On-device msfconsole round-trip is UNVERIFIED."
        "run_setoolkit_attack" ->
            "Runs against whichever backend is selected in Security tool settings: VictorSuite Kali via " +
                "RUN_COMMAND (TermuxSetExecutor, on-device setoolkit round-trip UNVERIFIED; only vectors " +
                "SetMenuPath has a real verified menu digit for — not SMS Spoofing, which was removed from " +
                "SET's current menu entirely), or a configured VictorLinux security backend " +
                "(LinuxBackendSetExecutor — only QRCODE_GENERATOR actually runs there; every other vector is " +
                "refused with a specific reason). No backend selected falls back to NullSetExecutor."
        "run_hackerai_agent_task" ->
            "HackerAI companion is not bound. A run is refused."
        "pentest_swarm_scan", "pentest_swarm_run_chain", "pentest_swarm_run_playbook" ->
            "Pentest-Swarm companion is not bound. A run is refused."
        else -> null
    }
}
