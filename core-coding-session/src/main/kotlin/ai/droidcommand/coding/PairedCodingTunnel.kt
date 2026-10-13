package ai.droidcommand.coding

import ai.droidcommand.remote.pairing.PairedConnection

/**
 * Client half of the coding tunnel over an already-paired [PairedConnection].
 *
 * The bytes are the same plan → edit → report session as [LocalCodingTunnel]. This does not know
 * how to reach Cursor, Codex, or any other vendor. The app does not open this connection; a caller
 * that already holds a paired socket can.
 */
class PairedCodingTunnel(private val connection: PairedConnection) {
    fun start(goal: String): CodingSession = roundTrip("START\n${CodingWire.escape(goal)}")

    fun advance(id: String): CodingSession = roundTrip("ADVANCE\n$id")

    fun cancel(id: String): CodingSession = roundTrip("CANCEL\n$id")

    private fun roundTrip(request: String): CodingSession {
        require(request.length <= CodingWire.MAX_MESSAGE_CHARS) { "Message is too long" }
        connection.send(request.toByteArray(Charsets.UTF_8))
        val reply = connection.receive()?.toString(Charsets.UTF_8)
            ?: throw IllegalStateException("Paired connection closed")
        if (reply.startsWith("ERR\n")) throw IllegalStateException(reply.removePrefix("ERR\n"))
        require(reply.startsWith("OK\n")) { "Unexpected tunnel reply" }
        return CodingWire.decode(reply.removePrefix("OK\n"))
    }
}

/**
 * Server half. Speaks the same messages as [PairedCodingTunnel] and answers from a [LocalCodingTunnel],
 * so a paired peer gets the on-device stand-in rather than a vendor cloud agent.
 */
class CodingTunnelPeer(private val tunnel: LocalCodingTunnel = LocalCodingTunnel()) {
    fun handle(connection: PairedConnection) {
        while (true) {
            val raw = connection.receive() ?: return
            val reply = try {
                "OK\n" + CodingWire.encode(dispatch(raw.toString(Charsets.UTF_8)))
            } catch (e: Exception) {
                "ERR\n" + (e.message ?: e.javaClass.simpleName).lineSequence().first()
            }
            connection.send(reply.toByteArray(Charsets.UTF_8))
        }
    }

    fun dispatch(message: String): CodingSession {
        require(message.length <= CodingWire.MAX_MESSAGE_CHARS) { "Message is too long" }
        val nl = message.indexOf('\n')
        val verb = if (nl < 0) message else message.substring(0, nl)
        val body = if (nl < 0) "" else message.substring(nl + 1)
        return when (verb) {
            "START" -> tunnel.start(CodingWire.unescape(body))
            "ADVANCE" -> tunnel.advance(body.trim())
            "CANCEL" -> tunnel.cancel(body.trim())
            else -> throw IllegalArgumentException("Unknown verb $verb")
        }
    }
}
