package ai.droidcommand.remotepc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SavedPcCommandsTest {
    @Test
    fun `round trip keeps commands`() {
        val list = listOf(SavedPcCommand("a", "Disk", "df -h"), SavedPcCommand("b", "Up", "uptime"))
        assertEquals(list, SavedPcCommands.decode(SavedPcCommands.encode(list)))
    }

    @Test
    fun `garbage or empty storage decodes to an empty list`() {
        assertTrue(SavedPcCommands.decode(null).isEmpty())
        assertTrue(SavedPcCommands.decode("not json").isEmpty())
    }

    @Test
    fun `commands that look like they hold secrets are refused`() {
        listOf(
            "sshpass -p hunter2 ssh pc",
            "export PASSWORD=hunter2",
            "curl --token abc https://x",
            "mysql -u root -phunter2",
            "git clone https://don:hunter2@example.com/r.git",
            "echo '-----BEGIN OPENSSH PRIVATE KEY-----'",
        ).forEach { assertIs<SaveCheck.Refused>(SavedPcCommands.check("x", it), it) }
    }

    @Test
    fun `blank or oversized input is refused and normal input is ok`() {
        assertIs<SaveCheck.Refused>(SavedPcCommands.check("", "ls"))
        assertIs<SaveCheck.Refused>(SavedPcCommands.check("x", " "))
        assertIs<SaveCheck.Refused>(SavedPcCommands.check("x".repeat(61), "ls"))
        assertEquals(SaveCheck.Ok, SavedPcCommands.check("Disk", "df -h"))
    }

    @Test
    fun `stored secret-looking entries are dropped on load`() {
        val raw = SavedPcCommands.encode(listOf(SavedPcCommand("a", "bad", "sshpass -p x ssh pc"), SavedPcCommand("b", "ok", "ls")))
        assertEquals(listOf("b"), SavedPcCommands.decode(raw).map { it.id })
    }
}
