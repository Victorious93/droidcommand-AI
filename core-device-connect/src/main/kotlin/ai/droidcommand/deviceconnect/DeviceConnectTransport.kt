package ai.droidcommand.deviceconnect

import ai.droidcommand.remote.pairing.SecureChannel
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class DeviceConnectException(message: String) : IOException(message)

/**
 * LAN listener / joiner. Off by default. Binds only while the owner is on
 * the Connect screen or [DeviceConnectService.keepListening] is on.
 * After pairing, only ping / status / send-a-note-to-chat are served.
 */
class DeviceConnectTransport(
    private val service: DeviceConnectService,
    private val localKind: DeviceKind,
    private val random: SecureRandom = SecureRandom(),
    private val onVerify: (code: String, peerName: String) -> Boolean = { _, _ -> true },
    private val allowControl: () -> Boolean = { false },
) : Closeable {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val executor = Executors.newCachedThreadPool()
    private val live = ConcurrentHashMap.newKeySet<Socket>()
    private val server = AtomicReference<ServerSocket?>(null)
    private val boundPort = AtomicReference<Int?>(null)
    private val running = AtomicBoolean(false)
    private val sessions = ConcurrentHashMap<String, OpenSession>()

    val isListening: Boolean get() = running.get()
    val port: Int? get() = boundPort.get()

    fun localAddresses(): List<String> = lanAddresses()

    fun invite(): String? {
        val p = port ?: return null
        val host = localAddresses().firstOrNull() ?: "127.0.0.1"
        return DeviceConnect.inviteOf(host, p, service.identity().deviceId)
    }

    fun startListening(bindPort: Int = DeviceConnect.DEFAULT_PORT): Int {
        if (running.get()) return checkNotNull(port)
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(bindPort))
        server.set(ss)
        boundPort.set(ss.localPort)
        running.set(true)
        service.setPhase(DeviceConnectPhase.LISTENING)
        executor.execute {
            while (running.get() && !ss.isClosed) {
                val socket = try {
                    ss.accept()
                } catch (_: IOException) {
                    break
                }
                live += socket
                executor.execute { handleIncoming(socket) }
            }
        }
        return ss.localPort
    }

    fun stopListening() {
        running.set(false)
        server.getAndSet(null)?.let { runCatching { it.close() } }
        boundPort.set(null)
        live.forEach { runCatching { it.close() } }
        live.clear()
        if (service.phase == DeviceConnectPhase.LISTENING) {
            service.setPhase(DeviceConnectPhase.IDLE)
        }
    }

    fun join(host: String, port: Int, password: String): DeviceConnectDecision {
        val gateKey = "$host:$port"
        service.lockoutDecision(gateKey)?.let { return it }
        if (password.length < DeviceConnect.MIN_PASSWORD_CHARS) {
            return DeviceConnectDecision.Refused("Use at least ${DeviceConnect.MIN_PASSWORD_CHARS} characters.")
        }
        service.setPhase(DeviceConnectPhase.JOINING)
        return try {
            val socket = Socket()
            socket.connect(InetSocketAddress(host, port), DeviceConnect.HANDSHAKE_TIMEOUT_MS)
            socket.soTimeout = DeviceConnect.HANDSHAKE_TIMEOUT_MS
            live += socket
            val session = handshakeAsInitiator(socket, password, gateKey)
            sessions[session.peer.deviceId] = session
            service.rememberSession(session.peer.displayName)
            DeviceConnectDecision.Ok("Paired with ${session.peer.displayName}.")
        } catch (e: DeviceConnectException) {
            service.setPhase(DeviceConnectPhase.FAILED, e.message)
            DeviceConnectDecision.Refused(e.message ?: "Pairing failed.")
        } catch (e: IOException) {
            service.setPhase(DeviceConnectPhase.FAILED, e.message)
            DeviceConnectDecision.Refused("Could not reach that address.")
        }
    }

    fun ping(deviceId: String): DeviceConnectDecision = request(deviceId, WireOp(op = "ping"))

    fun remoteStatus(deviceId: String): DeviceConnectDecision = request(deviceId, WireOp(op = "status"))

    fun sendChat(deviceId: String, text: String): DeviceConnectDecision =
        request(deviceId, WireOp(op = "chat_text", text = text))

    fun sendFile(deviceId: String, name: String, text: String): DeviceConnectDecision =
        request(deviceId, WireOp(op = "chat_file", name = name, text = text))

    override fun close() {
        stopListening()
        sessions.values.forEach { runCatching { it.close() } }
        sessions.clear()
        executor.shutdownNow()
    }

    private fun request(deviceId: String, op: WireOp): DeviceConnectDecision {
        val session = sessions[deviceId]
            ?: return DeviceConnectDecision.Refused("That device is not connected right now.")
        return try {
            session.send(json.encodeToString(op).toByteArray())
            val raw = session.receive() ?: return DeviceConnectDecision.Refused("The other device closed the connection.")
            val reply = json.decodeFromString(WireReply.serializer(), raw.decodeToString())
            if (reply.ok) DeviceConnectDecision.Ok(reply.message ?: "OK", reply.detail) else DeviceConnectDecision.Refused(reply.message ?: "Refused.")
        } catch (e: Exception) {
            DeviceConnectDecision.Refused(e.message ?: "The other device did not answer.")
        }
    }

    private fun handleIncoming(socket: Socket) {
        val gateKey = (socket.remoteSocketAddress as? InetSocketAddress)?.address?.hostAddress ?: "unknown"
        try {
            socket.soTimeout = DeviceConnect.HANDSHAKE_TIMEOUT_MS
            service.lockoutDecision(gateKey)?.let { locked ->
                val reason = (locked as? DeviceConnectDecision.Refused)?.reason ?: "Too many wrong passwords."
                reject(socket, reason)
                return
            }
            val session = handshakeAsResponder(socket, gateKey)
            sessions[session.peer.deviceId] = session
            service.rememberSession(session.peer.displayName)
            serve(session)
        } catch (_: Exception) {
            runCatching { socket.close() }
        } finally {
            live -= socket
        }
    }

    private fun handshakeAsInitiator(socket: Socket, password: String, gateKey: String): OpenSession {
        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
        val salt = Spake2.newSalt(random)
        val handshake = DeviceConnectHandshake(
            role = DeviceConnectHandshake.Role.INITIATOR,
            password = password,
            local = service.identity(),
            localKind = localKind,
            salt = salt,
            random = random,
        )
        writeJson(output, Frames.HELLO, handshake.localHello())
        val reply = readJson<HandshakeHello>(input, Frames.HELLO_REPLY)
        when (val accepted = handshake.acceptPeer(reply)) {
            is DeviceConnectDecision.Refused -> throw DeviceConnectException(accepted.reason)
            is DeviceConnectDecision.Ok -> Unit
        }
        val peerMac = hexToBytes(readJson<MacFrame>(input, Frames.CONFIRM).macHex)
        if (!handshake.verifyPeerMac(peerMac)) {
            service.failAttempt(gateKey, "The password did not match.")
            throw DeviceConnectException("The password did not match.")
        }
        writeJson(output, Frames.CONFIRM, MacFrame(bytesToHex(handshake.confirmMac())))
        val code = checkNotNull(handshake.verificationCode)
        service.rememberVerification(code, displayName(reply))
        if (!onVerify(code, displayName(reply))) {
            writeJson(output, Frames.REJECT, ErrorFrame("Codes did not match."))
            throw DeviceConnectException("The codes did not match.")
        }
        writeJson(output, Frames.SAS_OK, MacFrame(bytesToHex(DeviceIdentityKeys.sign(service.identity(), handshake.identityMessage()))))
        val peerSig = hexToBytes(readJson<MacFrame>(input, Frames.SAS_OK).macHex)
        return finishPaired(socket, input, output, handshake, reply, peerSig, gateKey)
    }

    private fun handshakeAsResponder(socket: Socket, gateKey: String): OpenSession {
        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
        val hello = readJson<HandshakeHello>(input, Frames.HELLO)
        val salt = runCatching { hexToBytes(hello.saltHex) }.getOrElse {
            throw DeviceConnectException("The other device sent a broken hello.")
        }
        if (salt.size != Spake2.SALT_BYTES) throw DeviceConnectException("The other device sent a broken hello.")
        val password = pendingPassword ?: throw DeviceConnectException("This phone is not waiting to pair.")
        val handshake = DeviceConnectHandshake(
            role = DeviceConnectHandshake.Role.RESPONDER,
            password = password,
            local = service.identity(),
            localKind = localKind,
            salt = salt,
            random = random,
        )
        handshake.localHello()
        when (val accepted = handshake.acceptPeer(hello)) {
            is DeviceConnectDecision.Refused -> throw DeviceConnectException(accepted.reason)
            is DeviceConnectDecision.Ok -> Unit
        }
        writeJson(output, Frames.HELLO_REPLY, handshake.localHello())
        writeJson(output, Frames.CONFIRM, MacFrame(bytesToHex(handshake.confirmMac())))
        val peerMac = hexToBytes(readJson<MacFrame>(input, Frames.CONFIRM).macHex)
        if (!handshake.verifyPeerMac(peerMac)) {
            service.failAttempt(gateKey, "The password did not match.")
            throw DeviceConnectException("The password did not match.")
        }
        val code = checkNotNull(handshake.verificationCode)
        service.rememberVerification(code, displayName(hello))
        if (!onVerify(code, displayName(hello))) {
            writeJson(output, Frames.REJECT, ErrorFrame("Codes did not match."))
            throw DeviceConnectException("The codes did not match.")
        }
        val peerSig = hexToBytes(readJson<MacFrame>(input, Frames.SAS_OK).macHex)
        writeJson(output, Frames.SAS_OK, MacFrame(bytesToHex(DeviceIdentityKeys.sign(service.identity(), handshake.identityMessage()))))
        return finishPaired(socket, input, output, handshake, hello, peerSig, gateKey)
    }

    private fun finishPaired(
        socket: Socket,
        input: DataInputStream,
        output: DataOutputStream,
        handshake: DeviceConnectHandshake,
        peerHello: HandshakeHello,
        peerSig: ByteArray,
        gateKey: String,
    ): OpenSession {
        if (!DeviceIdentityKeys.verify(peerHello.publicHex, handshake.peerIdentityMessage(), peerSig)) {
            service.failAttempt(gateKey, "The other device could not prove its key.")
            throw DeviceConnectException("The other device could not prove its key.")
        }
        val kind = runCatching { DeviceKind.valueOf(peerHello.kind) }.getOrDefault(DeviceKind.PHONE)
        val linked = service.link(peerHello.deviceId, displayName(peerHello), kind, peerHello.publicHex)
        if (linked is DeviceConnectDecision.Refused) throw DeviceConnectException(linked.reason)
        val (_, channel) = handshake.finishSession()
        socket.soTimeout = 0
        return OpenSession(socket, input, output, channel, TrustedDevice(peerHello.deviceId, displayName(peerHello), kind, peerHello.publicHex.lowercase(), 0L))
    }

    @Volatile
    var pendingPassword: String? = null

    fun preparePassword(password: String): DeviceConnectDecision {
        if (password.length < DeviceConnect.MIN_PASSWORD_CHARS) {
            return DeviceConnectDecision.Refused("Use at least ${DeviceConnect.MIN_PASSWORD_CHARS} characters.")
        }
        pendingPassword = password
        return DeviceConnectDecision.Ok("Password ready. Waiting for the other device.")
    }

    private fun serve(session: OpenSession) {
        while (true) {
            val raw = session.receive() ?: return
            val op = runCatching { json.decodeFromString(WireOp.serializer(), raw.decodeToString()) }
                .getOrElse {
                    session.send(json.encodeToString(WireReply(ok = false, message = "Unknown request.")).toByteArray())
                    continue
                }
            val reply = handleOp(session.peer, op)
            session.send(json.encodeToString(reply).toByteArray())
        }
    }

    private fun handleOp(peer: TrustedDevice, op: WireOp): WireReply {
        return when (op.op) {
            "ping" -> WireReply(ok = true, message = "Here.")
            "status" -> WireReply(
                ok = true,
                message = "This phone is paired and only answering safe checks.",
                detail = "phase=${service.phase};peers=${service.peers().size}",
            )
            "chat_text" -> {
                val result = service.postChat(peer.deviceId, peer.displayName, op.text.orEmpty())
                result.toReply()
            }
            "chat_file" -> {
                val result = service.postFile(peer.deviceId, peer.displayName, op.name.orEmpty(), op.text.orEmpty())
                result.toReply()
            }
            else -> {
                if (allowControl()) {
                    WireReply(ok = false, message = "That action still needs a separate confirmation in Settings.")
                } else {
                    WireReply(ok = false, message = "Remote control is off. Only status, ping, and sending a note to chat are allowed.")
                }
            }
        }
    }

    private fun DeviceConnectDecision.toReply(): WireReply = when (this) {
        is DeviceConnectDecision.Ok -> WireReply(ok = true, message = message, detail = extra)
        is DeviceConnectDecision.Refused -> WireReply(ok = false, message = reason)
    }

    private fun displayName(hello: HandshakeHello): String {
        val kind = runCatching { DeviceKind.valueOf(hello.kind) }.getOrNull()
        val label = when (kind) {
            DeviceKind.PC -> "Computer"
            DeviceKind.PHONE -> "Phone"
            null -> "Device"
        }
        return "$label ${hello.deviceId.take(6)}"
    }

    private fun reject(socket: Socket, reason: String) {
        runCatching {
            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
            writeJson(output, Frames.REJECT, ErrorFrame(reason))
        }
        runCatching { socket.close() }
    }

    private inline fun <reified T> readJson(input: DataInputStream, expected: Byte): T {
        val frame = readFrame(input)
        if (frame.type == Frames.REJECT) {
            val err = runCatching { json.decodeFromString(ErrorFrame.serializer(), frame.payload.decodeToString()) }.getOrNull()
            throw DeviceConnectException(err?.reason ?: "The other device refused.")
        }
        if (frame.type != expected) throw DeviceConnectException("The other device sent an unexpected message.")
        return json.decodeFromString(serializer<T>(), frame.payload.decodeToString())
    }

    private inline fun <reified T> writeJson(output: DataOutputStream, type: Byte, value: T) {
        writeFrame(output, type, json.encodeToString(serializer<T>(), value).toByteArray())
    }

    private object Frames {
        const val HELLO: Byte = 1
        const val HELLO_REPLY: Byte = 2
        const val CONFIRM: Byte = 3
        const val SAS_OK: Byte = 4
        const val RECORD: Byte = 5
        const val REJECT: Byte = 0x7F
        const val MAX = 8 * 1024
    }

    private class Frame(val type: Byte, val payload: ByteArray)

    private fun writeFrame(output: DataOutputStream, type: Byte, payload: ByteArray) {
        output.writeByte(type.toInt())
        output.writeInt(payload.size)
        output.write(payload)
        output.flush()
    }

    private fun readFrame(input: DataInputStream, max: Int = Frames.MAX): Frame {
        val type = input.read()
        if (type == -1) throw DeviceConnectException("The other device closed the connection.")
        val length = input.readInt()
        if (length < 0 || length > max) throw DeviceConnectException("The other device sent a message that is too large.")
        val payload = ByteArray(length)
        input.readFully(payload)
        return Frame(type.toByte(), payload)
    }

    private inner class OpenSession(
        private val socket: Socket,
        private val input: DataInputStream,
        private val output: DataOutputStream,
        private val channel: SecureChannel,
        val peer: TrustedDevice,
    ) : Closeable {
        fun send(message: ByteArray) {
            writeFrame(output, Frames.RECORD, channel.seal(message))
        }

        fun receive(): ByteArray? {
            return try {
                val frame = readFrame(input, 1 shl 20)
                if (frame.type != Frames.RECORD) throw DeviceConnectException("Unexpected message after pairing.")
                channel.open(frame.payload)
            } catch (_: IOException) {
                close()
                null
            }
        }

        override fun close() {
            channel.close()
            runCatching { socket.close() }
        }
    }

    @Serializable
    private data class MacFrame(val macHex: String)

    @Serializable
    private data class ErrorFrame(val reason: String)

    @Serializable
    internal data class WireOp(
        val op: String,
        val text: String? = null,
        val name: String? = null,
    )

    @Serializable
    internal data class WireReply(
        val ok: Boolean,
        val message: String? = null,
        val detail: String? = null,
    )

    companion object {
        fun lanAddresses(): List<String> {
            val found = mutableListOf<String>()
            val interfaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() }.getOrDefault(emptyList())
            for (nic in interfaces) {
                if (!nic.isUp || nic.isLoopback) continue
                for (addr in nic.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        found += addr.hostAddress
                    }
                }
            }
            if (found.isEmpty()) {
                found += InetAddress.getLoopbackAddress().hostAddress
            }
            return found.distinct()
        }
    }
}

private inline fun <reified T> serializer() = kotlinx.serialization.serializer<T>()
