package ai.droidcommand.metasploit

import kotlin.test.Test
import kotlin.test.assertFailsWith

class MetasploitCommandTest {
    @Test
    fun `accepts a well-formed exploit command`() {
        MetasploitCommand(
            moduleType = MetasploitModuleType.EXPLOIT,
            modulePath = "exploit/windows/smb/ms17_010_eternalblue",
            target = MetasploitTarget("10.0.0.5", 445),
            payload = "payload/windows/x64/meterpreter/reverse_tcp",
            options = mapOf("LHOST" to "10.0.0.1", "LPORT" to "4444"),
        )
    }

    @Test
    fun `rejects a blank target host`() {
        assertFailsWith<IllegalArgumentException> {
            MetasploitCommand(
                moduleType = MetasploitModuleType.EXPLOIT,
                modulePath = "exploit/windows/smb/ms17_010_eternalblue",
                target = MetasploitTarget(""),
            )
        }
    }

    @Test
    fun `rejects a modulePath whose prefix does not match moduleType`() {
        assertFailsWith<IllegalArgumentException> {
            MetasploitCommand(
                moduleType = MetasploitModuleType.AUXILIARY,
                modulePath = "exploit/windows/smb/ms17_010_eternalblue",
                target = MetasploitTarget("10.0.0.5"),
            )
        }
    }

    @Test
    fun `rejects a modulePath containing characters outside the safe pattern`() {
        assertFailsWith<IllegalArgumentException> {
            MetasploitCommand(
                moduleType = MetasploitModuleType.EXPLOIT,
                modulePath = "exploit/windows; set RHOSTS 0.0.0.0",
                target = MetasploitTarget("10.0.0.5"),
            )
        }
    }

    @Test
    fun `rejects a target host containing a semicolon that could inject a second msfconsole command`() {
        assertFailsWith<IllegalArgumentException> {
            MetasploitCommand(
                moduleType = MetasploitModuleType.EXPLOIT,
                modulePath = "exploit/windows/smb/ms17_010_eternalblue",
                target = MetasploitTarget("10.0.0.5; set RHOSTS 0.0.0.0"),
            )
        }
    }

    @Test
    fun `rejects a target host containing a newline`() {
        assertFailsWith<IllegalArgumentException> {
            MetasploitCommand(
                moduleType = MetasploitModuleType.EXPLOIT,
                modulePath = "exploit/windows/smb/ms17_010_eternalblue",
                target = MetasploitTarget("10.0.0.5\nset RHOSTS 0.0.0.0"),
            )
        }
    }

    @Test
    fun `rejects an option key outside the safe pattern`() {
        assertFailsWith<IllegalArgumentException> {
            MetasploitCommand(
                moduleType = MetasploitModuleType.EXPLOIT,
                modulePath = "exploit/windows/smb/ms17_010_eternalblue",
                target = MetasploitTarget("10.0.0.5"),
                options = mapOf("LHOST; set RHOSTS 0.0.0.0" to "10.0.0.1"),
            )
        }
    }

    @Test
    fun `rejects an option value containing a semicolon`() {
        assertFailsWith<IllegalArgumentException> {
            MetasploitCommand(
                moduleType = MetasploitModuleType.EXPLOIT,
                modulePath = "exploit/windows/smb/ms17_010_eternalblue",
                target = MetasploitTarget("10.0.0.5"),
                options = mapOf("LHOST" to "10.0.0.1; set RHOSTS 0.0.0.0"),
            )
        }
    }

    @Test
    fun `rejects a payload outside the safe module-path pattern`() {
        assertFailsWith<IllegalArgumentException> {
            MetasploitCommand(
                moduleType = MetasploitModuleType.EXPLOIT,
                modulePath = "exploit/windows/smb/ms17_010_eternalblue",
                target = MetasploitTarget("10.0.0.5"),
                payload = "payload/windows; set RHOSTS 0.0.0.0",
            )
        }
    }
}
