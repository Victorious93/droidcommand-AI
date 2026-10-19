package ai.droidcommand.rootforge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ClientTest {
    private fun client(t: FakeTransport, n: RootForgeNodeConfig = node()) = RootForgeClient(n, t) { "req-1" }

    @Test fun `request names the paired node and the exact operation`() {
        val t = FakeTransport { Result.success(okFrame("req-1", NODE_A, """{"devices":[]}""")) }
        client(t).listDevices()
        val sent = t.sent.single()
        assertTrue(""""target_node_id":"$NODE_A"""" in sent, sent)
        assertTrue(""""operation":"rootforge.devices.list"""" in sent, sent)
        assertTrue(""""protocol_major":1""" in sent, sent)
    }

    @Test fun `devices decode`() {
        val t = FakeTransport {
            Result.success(okFrame("req-1", NODE_A, """{"devices":[{"serial":"ABC","mode":"adb","state":"device","usable":true,"note":"","properties":{}}]}"""))
        }
        val r = assertIs<RootForgeResult.Success<DeviceListResult>>(client(t).listDevices())
        assertEquals("ABC", r.value.devices.single().serial)
    }

    @Test fun `response from a different node is rejected`() {
        val t = FakeTransport { Result.success(okFrame("req-1", NODE_B, """{"devices":[]}""")) }
        val r = assertIs<RootForgeResult.Failure>(client(t).listDevices())
        assertEquals(FailureKind.NODE_IDENTITY_MISMATCH, r.kind)
    }

    @Test fun `response without a node id is rejected`() {
        val t = FakeTransport { Result.success("""{"protocol_major":1,"request_id":"req-1","ok":true,"result":{"devices":[]}}""") }
        assertEquals(FailureKind.NODE_IDENTITY_MISMATCH, assertIs<RootForgeResult.Failure>(client(t).listDevices()).kind)
    }

    @Test fun `uncorrelated request id is rejected`() {
        val t = FakeTransport { Result.success(okFrame("someone-else", NODE_A, """{"devices":[]}""")) }
        assertEquals(FailureKind.MALFORMED_RESPONSE, assertIs<RootForgeResult.Failure>(client(t).listDevices()).kind)
    }

    @Test fun `incompatible protocol major is rejected`() {
        val t = FakeTransport { Result.success(okFrame("req-1", NODE_A, "{}", major = 2)) }
        assertEquals(FailureKind.PROTOCOL_MISMATCH, assertIs<RootForgeResult.Failure>(client(t).capabilities()).kind)
    }

    @Test fun `remote refusal keeps its category and is distinct from transport failure`() {
        val t = FakeTransport {
            Result.success("""{"protocol_major":1,"request_id":"req-1","node_id":"$NODE_A","ok":false,"error":{"category":"unauthorized","message":"controller is not authorized"}}""")
        }
        val r = assertIs<RootForgeResult.Failure>(client(t).capabilities())
        assertEquals(FailureKind.REMOTE_ERROR, r.kind)
        assertEquals("unauthorized", r.remoteCategory)
    }

    @Test fun `transport failure is reported as such`() {
        val t = FakeTransport { Result.failure(TransportException("no response (exit 255): Host key verification failed.")) }
        val r = assertIs<RootForgeResult.Failure>(client(t).capabilities())
        assertEquals(FailureKind.TRANSPORT, r.kind)
        assertTrue("Host key verification failed" in r.message)
    }

    @Test fun `garbage and ok-without-result are malformed`() {
        assertEquals(FailureKind.MALFORMED_RESPONSE, assertIs<RootForgeResult.Failure>(client(FakeTransport { Result.success("not json") }).capabilities()).kind)
        val noResult = FakeTransport { Result.success("""{"protocol_major":1,"request_id":"req-1","node_id":"$NODE_A","ok":true}""") }
        assertEquals(FailureKind.MALFORMED_RESPONSE, assertIs<RootForgeResult.Failure>(client(noResult).capabilities()).kind)
    }

    @Test fun `bad devices payload is malformed not a crash`() {
        val t = FakeTransport { Result.success(okFrame("req-1", NODE_A, """{"devices":"nope"}""")) }
        assertEquals(FailureKind.MALFORMED_RESPONSE, assertIs<RootForgeResult.Failure>(client(t).listDevices()).kind)
    }

    @Test fun `device info decodes and sends the right operation`() {
        val t = FakeTransport { Result.success(okFrame("req-1", NODE_A, """{"info":{"Model":"Pixel 7"}}""")) }
        val r = assertIs<RootForgeResult.Success<DeviceInfoResult>>(client(t).deviceInfo())
        assertEquals("Pixel 7", r.value.info["Model"])
        assertTrue(""""operation":"rootforge.device.info"""" in t.sent.single())
    }

    @Test fun `device info with no target connected decodes as empty`() {
        val t = FakeTransport { Result.success(okFrame("req-1", NODE_A, """{"info":{}}""")) }
        val r = assertIs<RootForgeResult.Success<DeviceInfoResult>>(client(t).deviceInfo())
        assertTrue(r.value.info.isEmpty())
    }

    @Test fun `adb shell sends the command as a parameter and decodes the result`() {
        val t = FakeTransport { Result.success(okFrame("req-1", NODE_A, """{"exit_code":0,"output":"hi","truncated":false}""")) }
        val r = assertIs<RootForgeResult.Success<AdbShellResult>>(client(t).adbShell("echo hi"))
        assertEquals(0, r.value.exitCode)
        assertEquals("hi", r.value.output)
        assertTrue(""""operation":"rootforge.adb.shell"""" in t.sent.single())
        assertTrue(""""command":"echo hi"""" in t.sent.single())
    }

    @Test fun `adb reboot defaults target to empty and decodes requested`() {
        val t = FakeTransport { Result.success(okFrame("req-1", NODE_A, """{"requested":"bootloader"}""")) }
        val r = assertIs<RootForgeResult.Success<RequestedResult>>(client(t).adbReboot("bootloader"))
        assertEquals("bootloader", r.value.requested)
        assertTrue(""""target":"bootloader"""" in t.sent.single())
    }

    @Test fun `fastboot reboot sends target and decodes requested`() {
        val t = FakeTransport { Result.success(okFrame("req-1", NODE_A, """{"requested":"system"}""")) }
        val r = assertIs<RootForgeResult.Success<RequestedResult>>(client(t).fastbootReboot("system"))
        assertEquals("system", r.value.requested)
        assertTrue(""""operation":"rootforge.fastboot.reboot"""" in t.sent.single())
    }

    @Test fun `bad adb shell payload is malformed not a crash`() {
        val t = FakeTransport { Result.success(okFrame("req-1", NODE_A, """{"exit_code":"nope"}""")) }
        assertEquals(FailureKind.MALFORMED_RESPONSE, assertIs<RootForgeResult.Failure>(client(t).adbShell("x")).kind)
    }
}
