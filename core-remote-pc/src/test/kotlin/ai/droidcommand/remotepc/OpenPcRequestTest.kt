package ai.droidcommand.remotepc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenPcRequestTest {
    @Test
    fun `terminal request uses spec action, package and extras`() {
        val request = OpenPcRequest.build("7Q2M4KX9TB5N", PcMode.TERMINAL)
        assertEquals("com.termux.victor.action.OPEN_PC", request.action)
        assertEquals("com.termux", request.targetPackage)
        assertEquals(mapOf("profileId" to "7Q2M4KX9TB5N", "mode" to "terminal"), request.extras)
    }

    @Test
    fun `sftp request carries an optional absolute path`() {
        assertEquals(mapOf("profileId" to "7Q2M4KX9TB5N", "mode" to "sftp"), OpenPcRequest.build("7Q2M4KX9TB5N", PcMode.SFTP).extras)
        assertEquals("/home/don", OpenPcRequest.build("7Q2M4KX9TB5N", PcMode.SFTP, "/home/don").extras["path"])
    }

    @Test
    fun `path is ignored for terminal and must be absolute for sftp`() {
        assertNull(OpenPcRequest.build("7Q2M4KX9TB5N", PcMode.TERMINAL, "/tmp").extras["path"])
        assertFailsWith<IllegalArgumentException> { OpenPcRequest.build("7Q2M4KX9TB5N", PcMode.SFTP, "relative") }
    }

    @Test
    fun `bad profile id is refused`() {
        assertFailsWith<IllegalArgumentException> { OpenPcRequest.build("", PcMode.TERMINAL) }
        assertFailsWith<IllegalArgumentException> { OpenPcRequest.build("7Q2M4KX9TB5N;x", PcMode.TERMINAL) }
    }

    @Test
    fun `v1 has no mirror mode`() {
        assertEquals(listOf("terminal", "sftp"), PcMode.entries.map { it.wire })
    }

    @Test
    fun `consent request targets VictorSuite`() {
        val consent = OpenPcRequest.consent()
        assertEquals("com.termux.victor.action.PC_CONSENT", consent.action)
        assertEquals("com.termux", consent.targetPackage)
        assertTrue(consent.extras.isEmpty())
    }

    @Test
    fun `result errors become plain messages and cancel is silent`() {
        assertNull(OpenPcResultCopy.message(ok = true, error = null, pcName = "PC"))
        assertNull(OpenPcResultCopy.message(ok = false, error = "user_cancelled", pcName = "PC"))
        assertNull(OpenPcResultCopy.message(ok = false, error = null, pcName = "PC"))
        listOf("consent_required", "not_allowed", "unknown_profile", "unsupported_mode", "unreachable", "host_key_mismatch", "bad_request", "weird").forEach {
            val text = OpenPcResultCopy.message(ok = false, error = it, pcName = "PC")
            assertTrue(!text.isNullOrBlank(), it)
        }
        assertTrue(OpenPcResultCopy.message(false, "host_key_mismatch", "PC")!!.contains("Pair it again"))
    }
}
