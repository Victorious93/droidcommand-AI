package ai.droidcommand.llm.pchost

import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * A model server under the test's control, on a raw socket so it can do what a real PC does and an HTTP library will
 * not: stay silent, or vanish in the middle of a reply. Loopback only; no real model, no real key.
 */
class FakePcServer(private val handler: (Request, Connection) -> Unit) : AutoCloseable {
    data class Request(val method: String, val path: String, val headers: Map<String, String>, val body: String)

    class Connection(private val socket: Socket) {
        private val out = socket.getOutputStream()

        fun respond(status: Int, body: String, contentType: String = "application/json") {
            val bytes = body.toByteArray()
            out.write("HTTP/1.1 $status X\r\nContent-Type: $contentType\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(bytes)
            out.flush()
        }

        fun startStream() {
            out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush()
        }

        fun chunk(text: String) {
            val bytes = text.toByteArray()
            out.write("${bytes.size.toString(16)}\r\n".toByteArray())
            out.write(bytes)
            out.write("\r\n".toByteArray())
            out.flush()
        }

        /** One `data:` event in the OpenAI streaming shape. */
        fun delta(text: String) = chunk("data: {\"choices\":[{\"delta\":{\"content\":\"$text\"}}]}\n\n")

        fun finish() {
            chunk("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n")
            chunk("data: [DONE]\n\n")
            endStream()
        }

        fun endStream() {
            out.write("0\r\n\r\n".toByteArray())
            out.flush()
        }

        /** The PC goes away: the connection is reset with no end-of-reply marker. */
        fun drop() {
            socket.setSoLinger(true, 0)
            socket.close()
        }

        /** Blocks until the client closes its side, or [millis] pass. True when it closed. */
        fun awaitClientGone(millis: Long): Boolean {
            socket.soTimeout = millis.toInt()
            return try {
                socket.getInputStream().read() == -1
            } catch (_: java.net.SocketTimeoutException) {
                false
            } catch (_: java.io.IOException) {
                true
            }
        }
    }

    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val requests = CopyOnWriteArrayList<Request>()
    val port: Int get() = server.localPort
    val address: String get() = "http://127.0.0.1:$port"

    /** Counted down by a handler that saw the client hang up. */
    val clientGone = CountDownLatch(1)

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (_: Exception) {
                    break
                }
                thread(isDaemon = true) {
                    socket.use {
                        runCatching {
                            val request = read(it.getInputStream()) ?: return@runCatching
                            requests += request
                            handler(request, Connection(it))
                        }
                    }
                }
            }
        }
    }

    fun awaitClientGone(seconds: Long = 5): Boolean = clientGone.await(seconds, TimeUnit.SECONDS)

    override fun close() = server.close()

    private fun read(input: InputStream): Request? {
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b == -1) return null
            head.append(b.toChar())
        }
        val lines = head.toString().trim().split("\r\n")
        val (method, path) = lines.first().split(" ")
        val headers = lines.drop(1).associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = String(input.readNBytes(length))
        return Request(method, path, headers, body)
    }

    companion object {
        /** A port nothing listens on: what a PC that is on, but not running the server, looks like. */
        fun closedPort(): Int = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
    }
}
