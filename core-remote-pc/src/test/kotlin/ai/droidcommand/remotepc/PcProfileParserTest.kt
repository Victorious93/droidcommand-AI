package ai.droidcommand.remotepc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PcProfileParserTest {
    private val c = PcProfileContract.Columns

    @Test
    fun `spec example row parses every column`() {
        val result = PcProfileParser.parseRow(row(c.LAST_SEEN_HOST to "100.101.7.12", c.LAST_SEEN_AT to "1762755700000", c.REACHABLE to "1", c.CALLER_ENROLLED to "0"))
        val p = assertIs<PcRowResult.Ok>(result).profile
        assertEquals("7Q2M4KX9TB5N", p.profileId)
        assertEquals("don-ubuntu", p.name)
        assertEquals("don", p.user)
        assertEquals(listOf("192.168.1.42", "don-ubuntu.local", "100.101.7.12"), p.hosts)
        assertEquals(22, p.port)
        assertEquals(listOf(HK), p.hostKeys)
        assertEquals(setOf("sftp"), p.caps)
        assertTrue(p.supportsSftp)
        assertEquals(1762755600000L, p.pairedAtMs)
        assertEquals("100.101.7.12", p.lastSeenHost)
        assertEquals(1762755700000L, p.lastSeenAtMs)
        assertEquals(CachedReachability.REACHABLE, p.reachable)
        assertFalse(p.callerEnrolled)
    }

    @Test
    fun `reachable maps 1, 0 and anything else to unknown`() {
        assertEquals(CachedReachability.UNREACHABLE, profile(c.REACHABLE to "0").reachable)
        assertEquals(CachedReachability.UNKNOWN, profile(c.REACHABLE to "-1").reachable)
        assertEquals(CachedReachability.UNKNOWN, profile(c.REACHABLE to null).reachable)
    }

    @Test
    fun `multiple host keys split on semicolon and empty caps are allowed`() {
        val ecdsa = "ecdsa-sha2-nistp256:SHA256:d6a1zp2bJmEDKLL7Rmm7p/5ZLuzzfyCvzWYJRMalj0U"
        val p = profile(c.HOST_KEYS to "$HK;$ecdsa", c.CAPS to "")
        assertEquals(listOf(HK, ecdsa), p.hostKeys)
        assertTrue(p.caps.isEmpty())
        assertFalse(p.supportsSftp)
    }

    @Test
    fun `last seen host that is not in hosts is dropped`() {
        assertNull(profile(c.LAST_SEEN_HOST to "10.0.0.9").lastSeenHost)
    }

    @Test
    fun `bracketed IPv6 host is accepted`() {
        assertEquals(listOf("[fd7a:115c:a1e0::1]"), profile(c.HOSTS to "[fd7a:115c:a1e0::1]").hosts)
    }

    @Test
    fun `bad rows are skipped with a reason`() {
        val bad = listOf(
            row(c.SCHEMA_VERSION to "2"),
            row(c.SCHEMA_VERSION to null),
            row(c.PROFILE_ID to "short"),
            row(c.PROFILE_ID to "7q2m4kx9tb5n"),
            row(c.NAME to ""),
            row(c.NAME to "a\u0007b"),
            row(c.USER to "Root"),
            row(c.HOSTS to ""),
            row(c.HOSTS to "http://evil/x"),
            row(c.HOSTS to (1..9).joinToString(",") { "10.0.0.$it" }),
            row(c.PORT to "0"),
            row(c.PORT to "70000"),
            row(c.HOST_KEYS to ""),
            row(c.HOST_KEYS to "ssh-ed25519 AAAAC3Nz"),
        )
        bad.forEach { assertIs<PcRowResult.Skipped>(PcProfileParser.parseRow(it), "should skip $it") }
    }

    @Test
    fun `parseRows sorts by paired_at, drops duplicates and counts skips`() {
        val (profiles, skipped) = PcProfileParser.parseRows(
            listOf(
                row(c.PROFILE_ID to "BBBBBBBBBBBB", c.PAIRED_AT to "20"),
                row(c.PROFILE_ID to "AAAAAAAAAAAA", c.PAIRED_AT to "10"),
                row(c.PROFILE_ID to "AAAAAAAAAAAA", c.PAIRED_AT to "30"),
                row(c.PORT to "nope"),
            ),
        )
        assertEquals(listOf("AAAAAAAAAAAA", "BBBBBBBBBBBB"), profiles.map { it.profileId })
        assertEquals(2, skipped.size)
    }
}
