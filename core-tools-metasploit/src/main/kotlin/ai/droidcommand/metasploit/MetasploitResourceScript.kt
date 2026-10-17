package ai.droidcommand.metasploit

/**
 * Builds the msfconsole resource-script lines from a validated
 * [MetasploitCommand]. Shared by [ShellBackedMetasploitExecutor] (`-x`)
 * and [TermuxMetasploitExecutor] (`-q -r`).
 */
object MetasploitResourceScript {
    fun lines(command: MetasploitCommand): List<String> = buildList {
        add("use ${command.modulePath}")
        add("set RHOSTS ${command.target.host}")
        command.target.port?.let { add("set RPORT $it") }
        command.payload?.let { add("set PAYLOAD $it") }
        command.options.forEach { (key, value) -> add("set $key $value") }
        add("run -z")
        add("exit -y")
    }
}
