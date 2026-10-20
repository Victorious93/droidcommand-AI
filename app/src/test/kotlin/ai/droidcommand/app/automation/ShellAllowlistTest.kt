package ai.droidcommand.app.automation

import kotlin.test.Test
import kotlin.test.assertEquals

class ShellAllowlistTest {
    @Test
    fun `parse trims, drops blank lines and duplicates, and sorts`() {
        assertEquals(listOf("/system/bin/ls", "echo"), ShellAllowlist.parse("  echo \n\n/system/bin/ls\necho\n").toList())
    }

    @Test
    fun `no stored value means nothing is allowed`() {
        assertEquals(emptySet(), ShellAllowlist.parse(null))
        assertEquals(emptySet(), ShellAllowlist.parse(""))
    }

    @Test
    fun `serialize drops entries that would break the one-per-line format`() {
        assertEquals("echo", ShellAllowlist.serialize(setOf("echo", "bad\nentry", "  ")))
    }

    @Test
    fun `serialize then parse round-trips`() {
        val entries = setOf("ls", "/bin/cat")
        assertEquals(entries, ShellAllowlist.parse(ShellAllowlist.serialize(entries)))
    }
}
