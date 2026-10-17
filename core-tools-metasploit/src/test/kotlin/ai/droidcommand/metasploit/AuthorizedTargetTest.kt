package ai.droidcommand.metasploit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AuthorizedTargetTest {
    @Test
    fun `accepts a single IPv4 host`() {
        assertEquals("10.0.0.5", AuthorizedTarget.requireSingleHost(" 10.0.0.5 "))
    }

    @Test
    fun `accepts a hostname`() {
        assertEquals("lab.example.com", AuthorizedTarget.requireSingleHost("lab.example.com"))
    }

    @Test
    fun `rejects blank`() {
        assertFailsWith<IllegalArgumentException> { AuthorizedTarget.requireSingleHost("  ") }
    }

    @Test
    fun `rejects CIDR`() {
        assertFailsWith<IllegalArgumentException> { AuthorizedTarget.requireSingleHost("10.0.0.0/24") }
    }

    @Test
    fun `rejects a comma list`() {
        assertFailsWith<IllegalArgumentException> { AuthorizedTarget.requireSingleHost("10.0.0.5,10.0.0.6") }
    }

    @Test
    fun `rejects an IPv4 range`() {
        assertFailsWith<IllegalArgumentException> { AuthorizedTarget.requireSingleHost("10.0.0.1-254") }
    }

    @Test
    fun `rejects 0_0_0_0`() {
        assertFailsWith<IllegalArgumentException> { AuthorizedTarget.requireSingleHost("0.0.0.0") }
    }

    @Test
    fun `rejects a wildcard`() {
        assertFailsWith<IllegalArgumentException> { AuthorizedTarget.requireSingleHost("*.example.com") }
    }
}
