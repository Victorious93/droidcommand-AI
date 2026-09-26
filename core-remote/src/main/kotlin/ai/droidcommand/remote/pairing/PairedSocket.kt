package ai.droidcommand.remote.pairing

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.SecureRandom
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** The other side refused to pair, or the handshake broke off. */
class PairingRejectedException(message: String) : IOException(message)

/**
 * The wire format [PairedSocketServer] and [PairedSocketClient] share: every
 * frame is `type (1 byte) || length (4 bytes, big-endian) || payload`.
 *
 * On connect: the controller sends [CLIENT_HELLO] (its peer id, then a
 * [PairingHandshake.ClientHello]); the device answers [SERVER_HELLO] or
 * [REJECT]; the controller sends [CLIENT_FINISH]; the device answers
 * [ACCEPT] or [REJECT]. After that, both sides exchange only [RECORD]
 * frames, each one a [SecureChannel] record.
 */
internal object Frames {
    const val CLIENT_HELLO: Byte = 1
    const val SERVER_HELLO: Byte = 2
    const val CLIENT_FINISH: Byte = 3
    const val ACCEPT: Byte = 4
    const val RECORD: Byte = 5
    const val REJECT: Byte = 0x7F

    /** Largest frame payload accepted: 1 MiB of plaintext plus a record's counter and tag. */
    const val MAX_PAYLOAD = (1 shl 20) + SecureChannel.COUNTER_BYTES + SecureChannel.TAG_BYTES
    const val MAX_PEER_ID_BYTES = 256

    class Frame(val type: Byte, val payload: ByteArray)

    fun write(out: DataOutputStream, type: Byte, payload: ByteArray = ByteArray(0)) {
        require(payload.size <= MAX_PAYLOAD) { "Frame payload of ${payload.size} bytes exceeds $MAX_PAYLOAD" }
        out.writeByte(type.toInt())
        out.writeInt(payload.size)
        out.write(payload)
        out.flush()
    }

    /** The next frame, or null when the peer closed the connection cleanly between frames. */
    fun read(input: DataInputStream): Frame? {
        val type = input.read()
        if (type == -1) return null
        val length = input.readInt()
        if (length < 0 || length > MAX_PAYLOAD) throw IOException("Frame length $length is out of range")
        val payload = ByteArray(length)
        input.readFully(payload)
        return Frame(type.toByte(), payload)
    }

    fun expect(input: DataInputStream, type: Byte, step: String): ByteArray {
        val frame = read(input) ?: throw PairingRejectedException("Connection closed during $step")
        if (frame.type == REJECT) throw PairingRejectedException(frame.payload.decodeToString())
        if (frame.type != type) throw IOException("Expected frame type $type during $step, got ${frame.type}")
        return frame.payload
    }

    fun encodeClientHello(peerId: String, hello: PairingHandshake.ClientHello): ByteArray {
        val id = peerId.toByteArray()
        require(peerId.isNotBlank() && id.size <= MAX_PEER_ID_BYTES) { "peerId must be 1..$MAX_PEER_ID_BYTES bytes" }
        return byteArrayOf((id.size shr 8).toByte(), id.size.toByte()) + id + hello.toBytes()
    }

    fun decodeClientHello(payload: ByteArray): Pair<String, PairingHandshake.ClientHello> {
        if (payload.size < 2) throw IOException("Client hello is too short")
        val idLength = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
        if (idLength == 0 || idLength > MAX_PEER_ID_BYTES || payload.size < 2 + idLength) {
            throw IOException("Client hello has an invalid peer id length $idLength")
        }
        val peerId = payload.copyOfRange(2, 2 + idLength).decodeToString()
        val hello = try {
            PairingHandshake.ClientHello.fromBytes(payload.copyOfRange(2 + idLength, payload.size))
        } catch (e: IllegalArgumentException) {
            throw IOException("Client hello is malformed: ${e.message}", e)
        }
        return peerId to hello
    }
}

/**
 * An authenticated, encrypted connection to one paired peer. [send] and
 * [receive] may be called from different threads; each is serialized on its
 * own. Closing the underlying [SecureChannel] (for example, by
 * [PairingRegistry.revoke]) closes the socket too, which unblocks a waiting
 * [receive]. A record that fails authentication closes the connection: a
 * tampered stream is never read past.
 */
class PairedConnection internal constructor(
    val peerId: String,
    private val socket: Socket,
    private val input: DataInputStream,
    private val output: DataOutputStream,
    private val channel: SecureChannel,
) : Closeable {
    private val sendLock = Any()
    private val receiveLock = Any()

    init {
        channel.onClose { runCatching { socket.close() } }
    }

    val isClosed: Boolean get() = channel.isClosed || socket.isClosed

    fun send(message: ByteArray) {
        synchronized(sendLock) {
            try {
                Frames.write(output, Frames.RECORD, channel.seal(message))
            } catch (e: IOException) {
                close()
                throw e
            }
        }
    }

    /** The next message, or null once the connection is closed by either side. */
    fun receive(): ByteArray? {
        synchronized(receiveLock) {
            if (isClosed) return null
            try {
                val frame = Frames.read(input) ?: return null.also { close() }
                if (frame.type != Frames.RECORD) throw IOException("Unexpected frame type ${frame.type} after pairing")
                return channel.open(frame.payload)
            } catch (e: SocketException) {
                if (isClosed) return null
                close()
                throw e
            } catch (e: EOFException) {
                close()
                return null
            } catch (e: Exception) {
                close()
                throw e
            }
        }
    }

    override fun close() {
        channel.close()
        runCatching { socket.close() }
    }
}

/**
 * The controller side: connects to a [PairedSocketServer], runs the
 * handshake as [peerId] with [secret], and returns the connection. Throws
 * [PairingRejectedException] when the device refuses or its proof is wrong.
 */
object PairedSocketClient {
    fun connect(
        address: InetSocketAddress,
        peerId: String,
        secret: PairingSecret,
        timeout: Duration = Duration.ofSeconds(10),
        random: SecureRandom = SecureRandom(),
    ): PairedConnection {
        val timeoutMillis = timeout.toMillis().toInt()
        val socket = Socket()
        try {
            socket.connect(address, timeoutMillis)
            socket.soTimeout = timeoutMillis
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
            val controller = PairingHandshake.Controller(secret, random)
            Frames.write(output, Frames.CLIENT_HELLO, Frames.encodeClientHello(peerId, controller.hello()))
            val serverHelloBytes = Frames.expect(input, Frames.SERVER_HELLO, "the handshake")
            val serverHello = try {
                PairingHandshake.ServerHello.fromBytes(serverHelloBytes)
            } catch (e: IllegalArgumentException) {
                throw PairingRejectedException("Device sent a malformed hello: ${e.message}")
            }
            val paired = when (val result = controller.finish(serverHello)) {
                is PairingHandshake.ControllerResult.Rejected -> throw PairingRejectedException(result.reason)
                is PairingHandshake.ControllerResult.Paired -> result
            }
            Frames.write(output, Frames.CLIENT_FINISH, paired.finish.toBytes())
            Frames.expect(input, Frames.ACCEPT, "the handshake")
            socket.soTimeout = 0
            return PairedConnection(peerId, socket, input, output, SecureChannel(paired.keys))
        } catch (e: Throwable) {
            runCatching { socket.close() }
            throw e
        }
    }
}

/**
 * The device side: accepts TCP connections, runs [PairingHandshake] against
 * [registry] on each, and hands every authenticated connection to
 * [onConnection] on its own virtual thread. The connection is closed when
 * [onConnection] returns.
 *
 * Authentication happens before [onConnection] ever runs; a peer that fails
 * the handshake gets a [Frames.REJECT] frame and a closed socket. Failures
 * are counted by [gate] per remote IP address rather than per claimed peer
 * id, so knowing a controller's id isn't enough to lock it out. A peer that
 * stalls mid-handshake is dropped after [handshakeTimeout].
 *
 * [start] binds to loopback by default; listening on a LAN address is an
 * explicit choice the caller makes by passing one.
 */
class PairedSocketServer(
    private val registry: PairingRegistry,
    private val gate: AuthGate = AuthGate(),
    private val handshakeTimeout: Duration = Duration.ofSeconds(10),
    random: SecureRandom = SecureRandom(),
    private val onConnection: (PairedConnection) -> Unit,
) : Closeable {
    private val device = PairingHandshake.Device(registry::secretFor, gate, random)
    private val serverSocket = ServerSocket()
    private val executor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()
    private val liveSockets = ConcurrentHashMap.newKeySet<Socket>()

    /** Binds and starts accepting. Returns the bound address (useful with port 0). */
    fun start(bindAddress: InetSocketAddress = InetSocketAddress(InetAddress.getLoopbackAddress(), 0)): InetSocketAddress {
        serverSocket.bind(bindAddress)
        executor.execute(::acceptLoop)
        return InetSocketAddress(serverSocket.inetAddress, serverSocket.localPort)
    }

    private fun acceptLoop() {
        while (!serverSocket.isClosed) {
            val socket = try {
                serverSocket.accept()
            } catch (e: IOException) {
                return
            }
            liveSockets += socket
            executor.execute { handle(socket) }
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = handshakeTimeout.toMillis().toInt()
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
            val connection = handshake(socket, input, output) ?: return
            socket.soTimeout = 0
            connection.use { onConnection(it) }
        } catch (e: IOException) {
            // A dropped, stalled or malformed connection; nothing was authenticated.
        } finally {
            runCatching { socket.close() }
            liveSockets -= socket
        }
    }

    private fun handshake(socket: Socket, input: DataInputStream, output: DataOutputStream): PairedConnection? {
        val (peerId, hello) = Frames.decodeClientHello(Frames.expect(input, Frames.CLIENT_HELLO, "the handshake"))
        val gateKey = (socket.remoteSocketAddress as? InetSocketAddress)?.address?.hostAddress ?: peerId
        val pending = when (val response = device.respond(peerId, hello, gateKey)) {
            is PairingHandshake.DeviceResponse.LockedOut -> return reject(output, "Too many failed attempts; try again later")
            is PairingHandshake.DeviceResponse.Rejected -> return reject(output, PAIRING_REJECTED)
            is PairingHandshake.DeviceResponse.Challenge -> {
                Frames.write(output, Frames.SERVER_HELLO, response.hello.toBytes())
                response.pending
            }
        }
        val finishBytes = Frames.expect(input, Frames.CLIENT_FINISH, "the handshake")
        val finish = try {
            PairingHandshake.ClientFinish.fromBytes(finishBytes)
        } catch (e: IllegalArgumentException) {
            return reject(output, PAIRING_REJECTED)
        }
        val keys = when (val result = pending.complete(finish)) {
            is PairingHandshake.DeviceResult.Paired -> result.keys
            is PairingHandshake.DeviceResult.LockedOut -> return reject(output, "Too many failed attempts; try again later")
            is PairingHandshake.DeviceResult.Rejected -> return reject(output, PAIRING_REJECTED)
        }
        val channel = try {
            registry.openChannel(peerId, keys)
        } catch (e: IllegalStateException) {
            return reject(output, PAIRING_REJECTED)
        }
        Frames.write(output, Frames.ACCEPT)
        return PairedConnection(peerId, socket, input, output, channel)
    }

    private fun reject(output: DataOutputStream, reason: String): PairedConnection? {
        runCatching { Frames.write(output, Frames.REJECT, reason.toByteArray()) }
        return null
    }

    /** Stops accepting and closes every open connection. */
    override fun close() {
        runCatching { serverSocket.close() }
        liveSockets.forEach { runCatching { it.close() } }
        executor.shutdownNow()
    }

    private companion object {
        // Deliberately the same text for an unknown peer and a wrong proof.
        const val PAIRING_REJECTED = "Pairing rejected"
    }
}
