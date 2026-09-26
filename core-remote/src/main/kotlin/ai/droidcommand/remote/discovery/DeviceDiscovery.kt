package ai.droidcommand.remote.discovery

import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * LAN discovery for `device-serve` instances, over plain UDP so it needs no mDNS library.
 *
 * A controller sends a [PROBE_SIZE]-byte probe (magic, version, a random nonce, zero padding) to
 * the broadcast address or to specific hosts. A discoverable device answers with the same nonce,
 * its display name and the TCP port its paired server listens on. The controller takes the host
 * from the reply's source address.
 *
 * Discovery only says where a device is. It carries no secret and grants nothing: connecting still
 * needs the controller id and pairing secret from the [ai.droidcommand.remote.pairing] handshake.
 * Two guards keep the responder from being abused:
 * - A reply is always smaller than the probe that caused it, so a spoofed probe can't be used to
 *   amplify traffic at a third party.
 * - Anything that isn't exactly a well-formed probe is dropped without an answer.
 */
object DeviceDiscovery {
    /** The UDP port a discoverable device listens on unless told otherwise. */
    const val DEFAULT_PORT = 7101

    /** Every probe is exactly this long, and every reply is shorter. */
    const val PROBE_SIZE = 512

    /** Longest display name a reply carries, in code points. Longer names are cut. */
    const val MAX_NAME_CODE_POINTS = 60

    internal const val VERSION: Byte = 1
    internal const val NONCE_BYTES = 16
    private val PROBE_MAGIC = "DCAI?".toByteArray(Charsets.US_ASCII)
    private val REPLY_MAGIC = "DCAI!".toByteArray(Charsets.US_ASCII)

    /** The address a probe goes to when no target is given. */
    fun broadcastTarget(port: Int = DEFAULT_PORT): InetSocketAddress =
        InetSocketAddress(InetAddress.getByName("255.255.255.255"), port)

    internal fun encodeProbe(nonce: ByteArray): ByteArray {
        require(nonce.size == NONCE_BYTES)
        return ByteBuffer.allocate(PROBE_SIZE).put(PROBE_MAGIC).put(VERSION).put(nonce).array()
    }

    /** The probe's nonce, or null when [bytes] isn't a probe this version understands. */
    internal fun decodeProbe(bytes: ByteArray): ByteArray? {
        if (bytes.size != PROBE_SIZE) return null
        if (!bytes.copyOfRange(0, PROBE_MAGIC.size).contentEquals(PROBE_MAGIC)) return null
        if (bytes[PROBE_MAGIC.size] != VERSION) return null
        val start = PROBE_MAGIC.size + 1
        return bytes.copyOfRange(start, start + NONCE_BYTES)
    }

    internal fun encodeReply(nonce: ByteArray, tcpPort: Int, name: String): ByteArray {
        require(tcpPort in 1..65535) { "TCP port $tcpPort is out of range" }
        val nameBytes = truncateName(name).toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(REPLY_MAGIC.size + 1 + NONCE_BYTES + 2 + 1 + nameBytes.size)
            .put(REPLY_MAGIC).put(VERSION).put(nonce)
            .putShort(tcpPort.toShort()).put(nameBytes.size.toByte()).put(nameBytes)
            .array()
    }

    internal class Reply(val nonce: ByteArray, val tcpPort: Int, val name: String)

    /** The reply, or null when [bytes] isn't a well-formed reply. */
    internal fun decodeReply(bytes: ByteArray): Reply? {
        val header = REPLY_MAGIC.size + 1 + NONCE_BYTES + 2 + 1
        if (bytes.size < header) return null
        if (!bytes.copyOfRange(0, REPLY_MAGIC.size).contentEquals(REPLY_MAGIC)) return null
        if (bytes[REPLY_MAGIC.size] != VERSION) return null
        val buffer = ByteBuffer.wrap(bytes, REPLY_MAGIC.size + 1, bytes.size - REPLY_MAGIC.size - 1)
        val nonce = ByteArray(NONCE_BYTES).also(buffer::get)
        val port = buffer.short.toInt() and 0xFFFF
        val nameLength = buffer.get().toInt() and 0xFF
        if (port == 0 || buffer.remaining() != nameLength) return null
        val name = ByteArray(nameLength).also(buffer::get).decodeToString()
        return Reply(nonce, port, name)
    }

    internal fun truncateName(name: String): String {
        val codePoints = name.codePoints().limit(MAX_NAME_CODE_POINTS.toLong()).toArray()
        return String(codePoints, 0, codePoints.size)
    }

    /**
     * Probes [targets] once each and collects every distinct device that answers within [timeout].
     * Replies that don't echo this call's nonce are ignored, so a stale or forged answer to someone
     * else's probe doesn't show up.
     */
    fun discover(
        targets: List<InetSocketAddress> = listOf(broadcastTarget()),
        timeout: Duration = Duration.ofSeconds(2),
        random: SecureRandom = SecureRandom(),
    ): List<DiscoveredDevice> {
        require(targets.isNotEmpty()) { "At least one target is required" }
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val probe = encodeProbe(nonce)
        val found = LinkedHashMap<InetSocketAddress, DiscoveredDevice>()
        DatagramSocket().use { socket ->
            socket.broadcast = true
            for (target in targets) {
                try {
                    socket.send(DatagramPacket(probe, probe.size, target))
                } catch (e: IOException) {
                    // One unreachable target (no route, broadcast not allowed) shouldn't stop the others.
                }
            }
            val deadline = System.nanoTime() + timeout.toNanos()
            val buffer = ByteArray(PROBE_SIZE)
            while (true) {
                val remainingMillis = (deadline - System.nanoTime()) / 1_000_000
                if (remainingMillis <= 0) break
                socket.soTimeout = remainingMillis.toInt().coerceAtLeast(1)
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    break
                }
                val reply = decodeReply(packet.data.copyOfRange(packet.offset, packet.offset + packet.length)) ?: continue
                if (!reply.nonce.contentEquals(nonce)) continue
                val address = InetSocketAddress(packet.address, reply.tcpPort)
                found.putIfAbsent(address, DiscoveredDevice(reply.name, address))
            }
        }
        return found.values.toList()
    }
}

/** A device that answered a discovery probe: its display name and where its paired server listens. */
data class DiscoveredDevice(val name: String, val address: InetSocketAddress)

/**
 * Answers discovery probes on behalf of a paired server listening on [tcpPort]. Off unless a
 * caller starts one: being discoverable is an explicit choice.
 */
class DiscoveryResponder(private val name: String, private val tcpPort: Int) : Closeable {
    private val socket = DatagramSocket(null)
    private val executor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()

    init {
        require(tcpPort in 1..65535) { "TCP port $tcpPort is out of range" }
    }

    /**
     * Binds and starts answering. The default listens on every interface at
     * [DeviceDiscovery.DEFAULT_PORT], which is what receiving LAN broadcasts needs. Returns the
     * bound address (useful with port 0).
     */
    fun start(bindAddress: InetSocketAddress = InetSocketAddress(DeviceDiscovery.DEFAULT_PORT)): InetSocketAddress {
        socket.reuseAddress = true
        socket.bind(bindAddress)
        executor.execute(::answerLoop)
        return InetSocketAddress(socket.localAddress, socket.localPort)
    }

    private fun answerLoop() {
        // One byte larger than a probe, so an oversized datagram is seen as oversized, not cut to fit.
        val buffer = ByteArray(DeviceDiscovery.PROBE_SIZE + 1)
        while (!socket.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (e: IOException) {
                if (socket.isClosed) return
                continue
            }
            val nonce = DeviceDiscovery.decodeProbe(packet.data.copyOfRange(packet.offset, packet.offset + packet.length))
                ?: continue
            val reply = DeviceDiscovery.encodeReply(nonce, tcpPort, name)
            try {
                socket.send(DatagramPacket(reply, reply.size, packet.socketAddress))
            } catch (e: IOException) {
                // The asker went away; keep answering others.
            }
        }
    }

    override fun close() {
        socket.close()
        executor.shutdownNow()
    }
}
