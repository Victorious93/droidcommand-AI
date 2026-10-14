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
            "No Termux backend is configured (NullTermuxExecutor). A run is refused."
        "run_metasploit_module" ->
            "No Metasploit program is connected (NullMetasploitExecutor). A run is refused."
        "run_setoolkit_attack" ->
            "No Social-Engineer Toolkit program is connected (NullSetExecutor). A run is refused."
        "run_hackerai_agent_task" ->
            "HackerAI companion is not bound. A run is refused."
        "pentest_swarm_scan", "pentest_swarm_run_chain", "pentest_swarm_run_playbook" ->
            "Pentest-Swarm companion is not bound. A run is refused."
        else -> null
    }
}
