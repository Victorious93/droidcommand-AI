package ai.droidcommand.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RemoteEndpointTest {
    @Test
    fun `accepts an https base URL`() {
        val endpoint = RemoteEndpoint("https://api.example.invalid")
        assertEquals("https://api.example.invalid", endpoint.baseUrl)
    }

    @Test
    fun `rejects a non-https base URL by default`() {
        assertFailsWith<InsecureEndpointRejected> {
            RemoteEndpoint("http://api.example.invalid")
        }
    }

    @Test
    fun `allows a non-https base URL when requireHttps is explicitly false`() {
        val endpoint = RemoteEndpoint("http://localhost:8080", requireHttps = false)
        assertEquals("http://localhost:8080", endpoint.baseUrl)
    }

    @Test
    fun `rejects a blank base URL`() {
        assertFailsWith<IllegalArgumentException> {
            RemoteEndpoint("   ", requireHttps = false)
        }
    }

    @Test
    fun `resolve joins baseUrl and path regardless of slashes`() {
        val endpoint = RemoteEndpoint("https://api.example.invalid/")
        assertEquals("https://api.example.invalid/v1/models", endpoint.resolve("/v1/models"))
        assertEquals("https://api.example.invalid/v1/models", endpoint.resolve("v1/models"))
    }

    @Test
    fun `resolve never escapes the configured host even with a suspicious path`() {
        val endpoint = RemoteEndpoint("https://good.example.invalid")
        val resolved = endpoint.resolve("http://evil.example.invalid/steal")
        assertTrue(resolved.startsWith("https://good.example.invalid/"))
    }
}
