package ai.droidcommand.termux

/**
 * VictorSuite is reached as [TermuxExecutionTarget] (RUN_COMMAND / plugin IPC), not a bound
 * service — see `docs/FLEET_CONTROL.md`'s `FleetIntegrationClass.TERMUX_IPC`. This probe follows
 * that: it is one `bash -lc "<script>"` command built here and sent through the existing
 * [TermuxExecutor]/RUN_COMMAND path, exactly the way [KaliRunCommandEnvelope] already wraps
 * `nmap`/`msfconsole` invocations. **VictorSuite itself needs no code change** for this —
 * no new bundled script, no new menu entry, no new bound service — the same way the Kali
 * first-win doc already notes for the plain RUN_COMMAND path.
 *
 * The script is read-only and bounded: it writes nothing, installs nothing, and every check is a
 * presence/listening check (`command -v`, `proot-distro login <distro> -- which <tool>`, a
 * `/proc/net/tcp` read for the ZTSocketService port). Output is `key=value`, one pair per line, so
 * parsing it needs no JSON dependency in this module.
 */
data class VictorSuiteCapabilities(
    val termuxPrefix: String?,
    val termuxHome: String?,
    val prootDistroInstalled: Boolean,
    val kaliInstalled: Boolean,
    val kaliHasNmap: Boolean,
    val kaliHasMsfconsole: Boolean,
    val ztCommandPortListening: Boolean,
) {
    /** One line per field, for a tool's text output or a log line. */
    fun describe(): String = buildString {
        appendLine("termux_prefix: ${termuxPrefix ?: "unknown"}")
        appendLine("termux_home: ${termuxHome ?: "unknown"}")
        appendLine("proot_distro_installed: $prootDistroInstalled")
        appendLine("kali_installed: $kaliInstalled")
        appendLine("kali_nmap: $kaliHasNmap")
        appendLine("kali_msfconsole: $kaliHasMsfconsole")
        append("ztcommand_port_listening: $ztCommandPortListening")
    }
}

object VictorSuiteCapabilitiesProbe {
    /**
     * `proot-distro login kali -- which <tool>` can cold-start the container the first time it is
     * invoked in a while, so this allows more headroom than a plain `which` would need; still
     * bounded, never open-ended.
     */
    const val DEFAULT_TIMEOUT_MILLIS = 20_000L

    /** ZTSocketService's fixed TCP port (19951, `0x4E9F`), read from `/proc/net/tcp` (hex, no DNS/socket needed). */
    private const val ZTCOMMAND_PORT_HEX = "4E9F"

    fun command(timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): TermuxCommand = TermuxCommand(
        executable = "bash",
        args = listOf("-lc", script),
        timeoutMillis = timeoutMillis,
    )

    /** Parses the probe's `key=value` stdout. Unknown/missing keys default to `false`/`null`, never throw. */
    fun parse(stdout: String): VictorSuiteCapabilities {
        val values = stdout.lineSequence()
            .mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx <= 0) null else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
            }
            .toMap()
        fun bool(key: String) = values[key] == "true"
        return VictorSuiteCapabilities(
            termuxPrefix = values["termux_prefix"]?.takeIf { it.isNotBlank() },
            termuxHome = values["termux_home"]?.takeIf { it.isNotBlank() },
            prootDistroInstalled = bool("proot_distro_installed"),
            kaliInstalled = bool("kali_installed"),
            kaliHasNmap = bool("kali_nmap"),
            kaliHasMsfconsole = bool("kali_msfconsole"),
            ztCommandPortListening = bool("ztcommand_port_listening"),
        )
    }

    private val script: String = """
        set -u
        PREFIX="${'$'}{PREFIX:-/data/data/com.termux/files/usr}"
        HOME_DIR="${'$'}{HOME:-/data/data/com.termux/files/home}"
        echo "termux_prefix=${'$'}PREFIX"
        echo "termux_home=${'$'}HOME_DIR"
        if command -v proot-distro >/dev/null 2>&1; then
          echo "proot_distro_installed=true"
        else
          echo "proot_distro_installed=false"
        fi
        ROOTFS_DIR="${'$'}PREFIX/var/lib/proot-distro/installed-rootfs"
        if [ -d "${'$'}ROOTFS_DIR/kali" ]; then
          echo "kali_installed=true"
          NMAP_OK=false
          MSF_OK=false
          if command -v proot-distro >/dev/null 2>&1; then
            proot-distro login kali -- which nmap >/dev/null 2>&1 && NMAP_OK=true
            proot-distro login kali -- which msfconsole >/dev/null 2>&1 && MSF_OK=true
          fi
          echo "kali_nmap=${'$'}NMAP_OK"
          echo "kali_msfconsole=${'$'}MSF_OK"
        else
          echo "kali_installed=false"
          echo "kali_nmap=false"
          echo "kali_msfconsole=false"
        fi
        if grep -q ":$ZTCOMMAND_PORT_HEX " /proc/net/tcp 2>/dev/null; then
          echo "ztcommand_port_listening=true"
        else
          echo "ztcommand_port_listening=false"
        fi
    """.trimIndent()
}
