package ai.droidcommand.remotecontrol

import kotlin.test.Test
import kotlin.test.assertTrue

class RemoteControlManualTest {
    @Test
    fun `manual names the ratified envelope and that payload fields do not authorize`() {
        val body = RemoteControlManual.BODY
        assertTrue(body.contains("requestId"))
        assertTrue(body.contains("targetNodeRef"))
        assertTrue(body.contains("actionRef"))
        assertTrue(body.contains("auth"))
        assertTrue(body.contains("nonce"))
        assertTrue(body.contains("issuedAt"))
        assertTrue(body.contains("ttlMs"))
        assertTrue(body.contains("advisory"))
        assertTrue(body.contains("package+cert"))
        assertTrue(body.contains("UNTRUSTED"))
        assertTrue(body.contains("not authorization"))
        assertTrue(body.contains("VictorSpoof"))
        assertTrue(body.contains("sms.remote_command.forward"))
        assertTrue(body.contains("[planned/pending-merge]"))
        assertTrue(body.contains("external-control"))
        assertTrue(body.contains("status tray") || body.contains("Status tray"))
        assertTrue(body.contains("YOLO"))
        assertTrue(body.contains("Shizuku"))
    }
}
