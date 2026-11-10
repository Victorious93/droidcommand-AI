package ai.droidcommand.remotepc

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/** Opens and immediately closes a TCP connection. Throws on failure. Blocking: never call on the main thread. */
fun interface TcpConnector {
    @Throws(IOException::class)
    fun connect(host: String, port: Int, timeoutMs: Int)
}

/** Real socket connect. Sends nothing: no SSH handshake, no banner read, no credentials. */
object SocketTcpConnector : TcpConnector {
    override fun connect(host: String, port: Int, timeoutMs: Int) {
        Socket().use { socket -> socket.connect(InetSocketAddress(host, port), timeoutMs) }
    }
}

sealed class PcOnlineState {
    data object Checking : PcOnlineState()
    data class Online(val host: String) : PcOnlineState()

    /** Nothing answered. [timedOut] is true when at least one host hit the timeout (vs. refused / no route). */
    data class Offline(val timedOut: Boolean) : PcOnlineState()
}

/**
 * "Is the PC up?" check: a TCP connect to the profile's sshd port (22 normally) on each host in the
 * preferred order, stopping at the first that answers. An open port is only a hint; VictorSuite still pins
 * the host key when it actually connects (§3, §10).
 */
class PcOnlineProbe(
    private val connector: TcpConnector = SocketTcpConnector,
    private val perHostTimeoutMs: Int = DEFAULT_PER_HOST_TIMEOUT_MS,
    private val totalBudgetMs: Long = DEFAULT_TOTAL_BUDGET_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init {
        require(perHostTimeoutMs > 0) { "perHostTimeoutMs must be positive" }
        require(totalBudgetMs > 0) { "totalBudgetMs must be positive" }
    }

    fun check(profile: PcProfile): PcOnlineState {
        val started = clock()
        var timedOut = false
        for (host in orderedHosts(profile)) {
            val left = totalBudgetMs - (clock() - started)
            if (left <= 0) {
                timedOut = true
                break
            }
            val timeout = minOf(perHostTimeoutMs.toLong(), left).toInt().coerceAtLeast(1)
            try {
                connector.connect(stripBrackets(host), profile.port, timeout)
                return PcOnlineState.Online(host)
            } catch (_: SocketTimeoutException) {
                timedOut = true
            } catch (_: IOException) {
                // Refused, no route, unknown host: try the next one.
            } catch (_: SecurityException) {
                // No INTERNET permission or a policy block: treat as not reachable.
            } catch (_: IllegalArgumentException) {
                // Unresolvable / invalid address.
            }
        }
        return PcOnlineState.Offline(timedOut)
    }

    /** Last host that answered with a pinned key first, then the profile's preferred order. */
    internal fun orderedHosts(profile: PcProfile): List<String> {
        val last = profile.lastSeenHost ?: return profile.hosts
        return listOf(last) + profile.hosts.filter { it != last }
    }

    private fun stripBrackets(host: String): String =
        if (host.startsWith("[") && host.endsWith("]")) host.substring(1, host.length - 1) else host

    companion object {
        const val DEFAULT_PER_HOST_TIMEOUT_MS = 1_500
        const val DEFAULT_TOTAL_BUDGET_MS = 4_000L
    }
}
