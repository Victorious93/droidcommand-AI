package ai.droidcommand.rootforge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProcessTransportTest {
    @Test fun `request line goes to stdin and first stdout line comes back`() {
        val t = ProcessRootForgeTransport(listOf("sh", "-c", "read l; echo \"echo:\$l\"; echo second"))
        assertEquals("echo:hello", t.exchange("hello", 5000).getOrThrow())
    }

    @Test fun `timeout kills the process`() {
        val t = ProcessRootForgeTransport(listOf("sh", "-c", "sleep 30"))
        val r = t.exchange("x", 300)
        assertTrue(r.isFailure)
        assertTrue("timed out" in r.exceptionOrNull()!!.message!!)
    }

    @Test fun `no output reports exit status and a bounded stderr hint`() {
        val t = ProcessRootForgeTransport(listOf("sh", "-c", "echo 'Host key verification failed.' >&2; exit 255"))
        val msg = t.exchange("x", 5000).exceptionOrNull()!!.message!!
        assertTrue("exit 255" in msg && "Host key verification failed" in msg, msg)
    }

    @Test fun `missing executable is a transport failure not a crash`() {
        val r = ProcessRootForgeTransport(listOf("/nonexistent/ssh")).exchange("x", 1000)
        assertTrue(r.isFailure)
    }

    @Test fun `oversized output is rejected`() {
        val t = ProcessRootForgeTransport(listOf("sh", "-c", "head -c 3000000 /dev/zero | tr '\\0' a; echo"), maxOutputBytes = 1000)
        val r = t.exchange("x", 10000)
        assertTrue(r.isFailure)
        assertTrue("exceeded" in r.exceptionOrNull()!!.message!!)
    }
}
