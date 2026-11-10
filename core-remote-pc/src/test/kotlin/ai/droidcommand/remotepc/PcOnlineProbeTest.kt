package ai.droidcommand.remotepc

import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PcOnlineProbeTest {
    private val c = PcProfileContract.Columns

    @Test
    fun `first host that answers wins and the profile port is used`() {
        val calls = mutableListOf<String>()
        val probe = PcOnlineProbe(connector = { host, port, _ ->
            calls += "$host:$port"
            if (host == "192.168.1.42") throw ConnectException("refused")
        })
        val state = probe.check(profile())
        assertEquals(PcOnlineState.Online("don-ubuntu.local"), state)
        assertEquals(listOf("192.168.1.42:22", "don-ubuntu.local:22"), calls)
    }

    @Test
    fun `timeouts on every host give offline with timedOut`() {
        val timeouts = mutableListOf<Int>()
        val probe = PcOnlineProbe(connector = { _, _, timeout ->
            timeouts += timeout
            throw SocketTimeoutException("connect timed out")
        }, perHostTimeoutMs = 500, totalBudgetMs = 10_000)
        val state = assertIs<PcOnlineState.Offline>(probe.check(profile()))
        assertTrue(state.timedOut)
        assertEquals(listOf(500, 500, 500), timeouts)
    }

    @Test
    fun `refused everywhere is offline without timeout`() {
        val probe = PcOnlineProbe(connector = { _, _, _ -> throw IOException("no route") })
        assertEquals(PcOnlineState.Offline(timedOut = false), probe.check(profile()))
    }

    @Test
    fun `total budget stops the probe and clamps the last timeout`() {
        var now = 0L
        val timeouts = mutableListOf<Int>()
        val probe = PcOnlineProbe(
            connector = { _, _, timeout ->
                timeouts += timeout
                now += timeout
                throw SocketTimeoutException()
            },
            perHostTimeoutMs = 1_500,
            totalBudgetMs = 2_000,
            clock = { now },
        )
        val state = assertIs<PcOnlineState.Offline>(probe.check(profile()))
        assertTrue(state.timedOut)
        assertEquals(listOf(1_500, 500), timeouts)
    }

    @Test
    fun `security and bad address errors do not crash the probe`() {
        val probe = PcOnlineProbe(connector = { host, _, _ ->
            if (host == "192.168.1.42") throw SecurityException("no INTERNET")
            throw IllegalArgumentException("unresolved")
        })
        assertEquals(PcOnlineState.Offline(timedOut = false), probe.check(profile()))
    }

    @Test
    fun `last seen host is tried first and IPv6 brackets are stripped`() {
        val calls = mutableListOf<String>()
        val probe = PcOnlineProbe(connector = { host, _, _ ->
            calls += host
            throw ConnectException()
        })
        probe.check(profile(c.HOSTS to "192.168.1.42,[fd7a::1]", c.LAST_SEEN_HOST to "[fd7a::1]"))
        assertEquals(listOf("fd7a::1", "192.168.1.42"), calls)
    }

    @Test
    fun `real socket connector reaches a local listener and reports a closed port`() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val open = PcOnlineProbe().check(profile(c.HOSTS to "127.0.0.1", c.PORT to server.localPort.toString()))
            assertEquals(PcOnlineState.Online("127.0.0.1"), open)
        }
        val closedPort = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        assertIs<PcOnlineState.Offline>(PcOnlineProbe().check(profile(c.HOSTS to "127.0.0.1", c.PORT to closedPort.toString())))
    }

    @Test
    fun `timeouts must be positive`() {
        assertFailsWith<IllegalArgumentException> { PcOnlineProbe(perHostTimeoutMs = 0) }
        assertFailsWith<IllegalArgumentException> { PcOnlineProbe(totalBudgetMs = 0) }
    }
}
