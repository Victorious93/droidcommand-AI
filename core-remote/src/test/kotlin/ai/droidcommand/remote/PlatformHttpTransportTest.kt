package ai.droidcommand.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlatformHttpTransportTest {
    @Test
    fun `this JVM is not an Android runtime`() {
        assertFalse(PlatformHttpTransport.runningOnAndroid())
        assertTrue(runCatching { Class.forName("android.os.Build") }.isFailure)
    }

    @Test
    fun `create on a desktop JVM returns JdkHttpTransport`() {
        assertIs<JdkHttpTransport>(PlatformHttpTransport.create())
    }

    @Test
    fun `HttpUrlConnectionTransport is the Android-capable StreamingHttpTransport`() {
        val transport: StreamingHttpTransport = HttpUrlConnectionTransport()
        assertEquals("HttpUrlConnectionTransport", transport::class.simpleName)
    }
}
