package ai.droidcommand.termux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProotDistroKaliEnvelopeTest {
    private val envelope = ProotDistroKaliEnvelope()

    @Test
    fun `nmap uses argv after login kali -- not a bash pipeline`() {
        val command = envelope.nmap("10.0.0.5", listOf("-sV", "-Pn", "--open", "-p", "1-1024"), 30_000)
        assertEquals(RunCommandContract.PROOT_DISTRO_PATH, command.executable)
        assertEquals(
            listOf("login", "kali", "--", "nmap", "-sV", "-Pn", "--open", "-p", "1-1024", "10.0.0.5"),
            command.args,
        )
        assertEquals(RunCommandContract.DEFAULT_WORKDIR, command.workingDirectory)
        val request = RunCommandRequest.from(command)
        assertEquals(RunCommandContract.PROOT_DISTRO_PATH, request.path)
        assertTrue(request.background)
        assertEquals(
            1,
            command.args.count { it == "nmap" },
            "proot-distro argv must contain nmap once after login kali --",
        )
    }

    @Test
    fun `msfconsole resource script uses bash -lc pipeline wrap`() {
        val command = envelope.msfconsoleResourceScript(
            listOf("use auxiliary/scanner/portscan/tcp", "set RHOSTS 10.0.0.5", "run -z", "exit -y"),
            60_000,
        )
        assertEquals(RunCommandContract.PROOT_DISTRO_PATH, command.executable)
        assertEquals("login", command.args[0])
        assertEquals("kali", command.args[1])
        assertEquals("--", command.args[2])
        assertEquals("bash", command.args[3])
        assertEquals("-lc", command.args[4])
        val pipeline = command.args[5]
        assertTrue(pipeline.contains("msfconsole -q -r"))
        assertTrue(pipeline.contains("use auxiliary/scanner/portscan/tcp"))
        assertTrue(pipeline.contains("set RHOSTS 10.0.0.5"))
    }
}
