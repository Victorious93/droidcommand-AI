package ai.droidcommand.termux

/**
 * A command that must run **inside** the Kali proot, not in Termux userland.
 * [argv] is the program and its arguments after `proot-distro login <distro> --`.
 */
data class KaliCommand(
    val argv: List<String>,
    val timeoutMillis: Long = 120_000,
) {
    init {
        require(argv.isNotEmpty()) { "Kali command argv must not be empty" }
        require(argv.first().isNotBlank()) { "Kali command executable must not be blank" }
    }
}

/**
 * Isolates how a Kali command becomes a [TermuxCommand] for VictorSuite
 * RUN_COMMAND, and how a result Bundle is read back.
 *
 * **[Certain]** wrapping shape, documented by VictorSuite for the Kali first-win:
 * - PATH = [RunCommandContract.PROOT_DISTRO_PATH]
 * - ARGUMENTS = `login`, `<distro>`, `--`, then either the tool argv
 *   (e.g. `nmap -sV <target>`) or `bash -lc <pipeline>` when redirection
 *   is required (msfconsole `-q -r` resource scripts).
 *
 * Swap this implementation if the Kali login distro name or proot-distro
 * path changes; do not scatter those strings through Metasploit/playbook code.
 */
interface KaliRunCommandEnvelope {
    fun toTermuxCommand(command: KaliCommand): TermuxCommand

    fun nmap(host: String, nmapArgs: List<String>, timeoutMillis: Long): TermuxCommand

    fun msfconsoleResourceScript(scriptLines: List<String>, timeoutMillis: Long): TermuxCommand

    /**
     * Pipes [stdinLines] into `setoolkit`'s stdin, one per line, exactly as if an operator had typed each line at
     * its interactive menu. SET has no official non-interactive flag; this is the real mechanical fact that makes
     * automating it possible at all — it is a plain Python CLI reading `input()`, so stdin substitutes for a
     * keyboard. What goes on a given version's screen at a given menu depth is not asserted here; see
     * [ai.droidcommand.setoolkit.SetMenuPath]'s and [ai.droidcommand.setoolkit.TermuxSetExecutor]'s doc comments.
     */
    fun setoolkitStdinSequence(stdinLines: List<String>, timeoutMillis: Long): TermuxCommand

    fun interpret(bundle: RunCommandResultBundle, durationMillis: Long): TermuxExecutionResult =
        bundle.toExecutionResult(durationMillis)
}

/**
 * Default Kali envelope: `proot-distro login kali -- …`.
 *
 * Distro name `kali` matches the documented bootstrap
 * (`proot-distro install kalilinux/kali-rolling --name kali`).
 */
class ProotDistroKaliEnvelope(
    private val distro: String = "kali",
    private val prootDistroPath: String = RunCommandContract.PROOT_DISTRO_PATH,
    private val workdir: String = RunCommandContract.DEFAULT_WORKDIR,
) : KaliRunCommandEnvelope {
    override fun toTermuxCommand(command: KaliCommand): TermuxCommand = TermuxCommand(
        executable = prootDistroPath,
        args = listOf("login", distro, "--") + command.argv,
        workingDirectory = workdir,
        timeoutMillis = command.timeoutMillis,
    )

    override fun nmap(host: String, nmapArgs: List<String>, timeoutMillis: Long): TermuxCommand {
        require(host.isNotBlank()) { "nmap host must not be blank" }
        require(nmapArgs.none { it == "--" }) { "nmap args must not contain -- (reserved for proot-distro)" }
        val flags = nmapArgs.filter { it != "nmap" }
        return toTermuxCommand(
            KaliCommand(argv = listOf("nmap") + flags + host, timeoutMillis = timeoutMillis),
        )
    }

    /**
     * `msfconsole -q -r` needs a file inside Kali, so this uses the documented
     * pipeline wrap: `bash -lc <script>`. The resource-script body is passed
     * through a quoted-delimiter heredoc so datastore values cannot expand.
     */
    override fun msfconsoleResourceScript(scriptLines: List<String>, timeoutMillis: Long): TermuxCommand {
        require(scriptLines.isNotEmpty()) { "msfconsole resource script must not be empty" }
        val delimiter = "DCA_MSF_EOF"
        require(scriptLines.none { it.contains(delimiter) }) {
            "resource script contains the heredoc delimiter"
        }
        val body = scriptLines.joinToString("\n")
        val pipeline = "rc=/tmp/dca-msf.rc\ncat > \"\$rc\" <<'$delimiter'\n$body\n$delimiter\nmsfconsole -q -r \"\$rc\""
        return toTermuxCommand(
            KaliCommand(argv = listOf("bash", "-lc", pipeline), timeoutMillis = timeoutMillis),
        )
    }

    /**
     * Same heredoc-into-a-file shape as [msfconsoleResourceScript], but the file becomes `setoolkit`'s stdin rather
     * than a resource-script argument: `setoolkit < "$in"`. A blank [stdinLines] still runs `setoolkit` with stdin
     * from `/dev/null`: that reaches its first menu prompt, reads EOF, and exits — it is never skipped silently.
     */
    override fun setoolkitStdinSequence(stdinLines: List<String>, timeoutMillis: Long): TermuxCommand {
        val delimiter = "DCA_SET_EOF"
        require(stdinLines.none { it.contains(delimiter) }) {
            "stdin sequence contains the heredoc delimiter"
        }
        val pipeline = if (stdinLines.isEmpty()) {
            "setoolkit < /dev/null"
        } else {
            val body = stdinLines.joinToString("\n")
            "in=/tmp/dca-set.in\ncat > \"\$in\" <<'$delimiter'\n$body\n$delimiter\nsetoolkit < \"\$in\""
        }
        return toTermuxCommand(
            KaliCommand(argv = listOf("bash", "-lc", pipeline), timeoutMillis = timeoutMillis),
        )
    }
}
