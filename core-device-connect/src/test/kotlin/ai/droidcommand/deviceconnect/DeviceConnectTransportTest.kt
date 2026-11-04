package ai.droidcommand.deviceconnect

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DeviceConnectTransportTest {
    @Test
    fun `loopback pairing then ping and a chat note`() {
        val listenerSvc = DeviceConnectService(InMemoryDeviceConnectStore())
        val joinerSvc = DeviceConnectService(InMemoryDeviceConnectStore())
        val ready = CountDownLatch(1)
        val listener = DeviceConnectTransport(
            service = listenerSvc,
            localKind = DeviceKind.PHONE,
            onVerify = { _, _ -> true },
        )
        val joiner = DeviceConnectTransport(
            service = joinerSvc,
            localKind = DeviceKind.PC,
            onVerify = { _, _ ->
                ready.countDown()
                true
            },
        )
        listener.use {
            joiner.use {
                listener.preparePassword("loopback-password")
                val port = listener.startListening(0)
                val joined = joiner.join("127.0.0.1", port, "loopback-password")
                assertIs<DeviceConnectDecision.Ok>(joined)
                assertTrue(ready.await(5, TimeUnit.SECONDS))
                // The waiter writes its last handshake frame, then stores the peer.
                // join() can return on the joiner before that store finishes.
                val deadline = System.currentTimeMillis() + 5_000
                while (listenerSvc.peers().isEmpty() && System.currentTimeMillis() < deadline) {
                    Thread.sleep(20)
                }
                val peerId = listenerSvc.peers().single().deviceId
                // Joiner is the client; it talks to the listening phone.
                val listenPeer = joinerSvc.peers().single().deviceId
                val ping = joiner.ping(listenPeer)
                assertIs<DeviceConnectDecision.Ok>(ping)
                val note = joiner.sendChat(listenPeer, "from the computer")
                assertIs<DeviceConnectDecision.Ok>(note)
                val inbox = listenerSvc.inbox().drain()
                assertEquals("from the computer", inbox.single().text)
                assertEquals(peerId, joinerSvc.identity().deviceId)
            }
        }
    }

    @Test
    fun `wrong password is refused`() {
        val listenerSvc = DeviceConnectService(InMemoryDeviceConnectStore())
        val joinerSvc = DeviceConnectService(InMemoryDeviceConnectStore())
        val listener = DeviceConnectTransport(listenerSvc, DeviceKind.PHONE, onVerify = { _, _ -> true })
        val joiner = DeviceConnectTransport(joinerSvc, DeviceKind.PC, onVerify = { _, _ -> true })
        listener.use {
            joiner.use {
                listener.preparePassword("loopback-password")
                val port = listener.startListening(0)
                val joined = joiner.join("127.0.0.1", port, "wrong-password")
                assertIs<DeviceConnectDecision.Refused>(joined)
            }
        }
    }
}
