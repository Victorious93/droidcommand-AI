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
        assertTrue(body.contains("nonce"))
        assertTrue(body.contains("timestampMs"))
        assertTrue(body.contains("ttlMs"))
        assertTrue(body.contains("future-skew"))
        assertTrue(body.contains("camelCase"))
        assertTrue(body.contains("snake_case"))
        assertTrue(body.contains("invalid_parameters"))
        assertTrue(body.contains("sourceNodeRef"))
        assertTrue(body.contains("advisory"))
        assertTrue(body.contains("package+cert"))
        assertTrue(body.contains("UNTRUSTED"))
        assertTrue(body.contains("not authorization"))
        assertTrue(body.contains("VictorSpoof"))
        assertTrue(body.contains("Victorious Flash"))
        assertTrue(body.contains("com.victorious.flash.android"))
        assertTrue(body.contains("FLEET_BRIDGE"))
        assertTrue(body.contains("gadget.expose"))
        assertTrue(body.contains("sms.remote_command.forward"))
        assertTrue(body.contains("[planned/pending-merge]"))
        assertTrue(body.contains("KYC-AuditLab"))
        assertTrue(body.contains("com.identityshield.companion"))
        assertTrue(body.contains("[provisional/pending-stable-cert]"))
        assertTrue(body.contains("remoteAllowed:false"))
        assertTrue(body.contains("per-machine default debug.keystore"))
        assertTrue(body.contains("KYC PR #29"))
        assertTrue(body.contains("external-control"))
        assertTrue(body.contains("status tray") || body.contains("Status tray"))
        assertTrue(body.contains("YOLO"))
        assertTrue(body.contains("Shizuku"))
    }
}
