package ai.droidcommand.remotecontrol

import kotlin.test.Test
import kotlin.test.assertTrue

class RemoteControlManualTest {
    @Test
    fun `manual names the frozen envelope and that payload fields do not authorize`() {
        val body = RemoteControlManual.BODY
        assertTrue(body.contains("requestId"))
        assertTrue(body.contains("targetNodeRef"))
        assertTrue(body.contains("actionRef"))
        assertTrue(body.contains("nonce"))
        assertTrue(body.contains("timestampMs"))
        assertTrue(body.contains("ttlMs"))
        assertTrue(body.contains("advisory"))
        assertTrue(body.contains("package+cert"))
        assertTrue(body.contains("never a"))
        assertTrue(body.contains("payload field"))
        assertTrue(body.contains("VictorSpoof"))
    }
}
