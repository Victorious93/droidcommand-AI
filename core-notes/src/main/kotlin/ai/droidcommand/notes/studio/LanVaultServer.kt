package ai.droidcommand.notes.studio

import ai.droidcommand.notes.NoteVault
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A read-only web view of the notes vault for other devices on the same Wi-Fi.
 *
 * Off until the user starts it. Every request needs HTTP Basic auth with the password the user set (at least
 * [MIN_PASSWORD] characters, compared in constant time). Only GET and HEAD are served; anything else gets 405.
 * Clients outside private, link-local or loopback ranges are dropped before the request is read, and the socket
 * binds only to the address passed in (the phone's Wi-Fi address), never to all interfaces by default.
 */
class LanVaultServer(
    private val vault: NoteVault,
    private val password: String,
    private val bindAddress: InetAddress,
    private val port: Int = DEFAULT_PORT,
    private val renderer: (String) -> String = { MermaidHtml.page(it, dark = false).replace(MermaidHtml.SCRIPT_URL, "/mermaid.js") },
    private val mermaidScript: () -> ByteArray? = { null },
) {
    private val running = AtomicBoolean(false)
    private var socket: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(4) { r -> Thread(r, "lan-vault").apply { isDaemon = true } }

    init {
        require(password.length >= MIN_PASSWORD) { "Use a password of at least $MIN_PASSWORD characters" }
        require(isLanAddress(bindAddress)) { "The viewer only binds to a local network address" }
    }

    val isRunning: Boolean get() = running.get()

    /** The port actually bound, useful when [port] is 0. */
    val boundPort: Int get() = socket?.localPort ?: -1

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress(bindAddress, port), 16)
        socket = server
        Thread({
            while (running.get()) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                pool.execute { client.use { handle(it) } }
            }
        }, "lan-vault-accept").apply { isDaemon = true }.start()
    }

    fun stop() {
        running.set(false)
        runCatching { socket?.close() }
        socket = null
        pool.shutdownNow()
    }

    private fun handle(client: Socket) {
        val remote = client.inetAddress
        if (!isLanAddress(remote)) return
        client.soTimeout = 10_000
        val out = client.getOutputStream()
        try {
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty() || headers.size > 64) break
                val i = line.indexOf(':')
                if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            }
            val parts = requestLine.split(' ')
            if (parts.size < 2) return send(out, 400, "text/plain", "Bad request".toByteArray())
            val method = parts[0]
            if (!authorized(headers["authorization"])) {
                return send(out, 401, "text/plain", "Password required".toByteArray(), "WWW-Authenticate: Basic realm=\"DroidCommand vault (read-only)\"\r\n")
            }
            if (method != "GET" && method != "HEAD") return send(out, 405, "text/plain", "This viewer is read-only".toByteArray(), "Allow: GET, HEAD\r\n")
            val target = parts[1].substringBefore('?')
            val (status, type, body) = route(target)
            send(out, status, type, body, head = method == "HEAD")
        } catch (_: SocketTimeoutException) {
            // Slow client; drop it.
        }
    }

    private fun route(target: String): Triple<Int, String, ByteArray> {
        if (target == "/mermaid.js") {
            val js = mermaidScript() ?: return Triple(404, "text/plain", "Not found".toByteArray())
            return Triple(200, "application/javascript", js)
        }
        if (target == "/" || target.isEmpty()) return Triple(200, HTML, index().toByteArray())
        if (target.startsWith("/note/")) {
            val path = URLDecoder.decode(target.removePrefix("/note/"), "UTF-8")
            val text = runCatching { vault.read(path) }.getOrNull() ?: return Triple(404, "text/plain", "Not found".toByteArray())
            return Triple(200, HTML, renderer(text).toByteArray())
        }
        return Triple(404, "text/plain", "Not found".toByteArray())
    }

    private fun index(): String {
        val items = vault.listAllMarkdown().sorted().joinToString("\n") { path ->
            "<li><a href=\"/note/${URLEncoder.encode(path, "UTF-8").replace("+", "%20")}\">${MermaidHtml.escape(path)}</a></li>"
        }
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>Vault (read-only)</title></head>" +
            "<body><h1>Vault (read-only)</h1><ul>\n$items\n</ul></body></html>"
    }

    private fun authorized(header: String?): Boolean {
        if (header == null || !header.startsWith("Basic ")) return false
        val decoded = runCatching { String(Base64.getDecoder().decode(header.removePrefix("Basic ").trim()), StandardCharsets.UTF_8) }.getOrNull() ?: return false
        val given = decoded.substringAfter(':', "")
        return MessageDigest.isEqual(given.toByteArray(), password.toByteArray())
    }

    private fun send(out: OutputStream, status: Int, type: String, body: ByteArray, extra: String = "", head: Boolean = false) {
        val reason = mapOf(200 to "OK", 400 to "Bad Request", 401 to "Unauthorized", 404 to "Not Found", 405 to "Method Not Allowed")[status] ?: "Error"
        val headers = "HTTP/1.1 $status $reason\r\nContent-Type: $type; charset=utf-8\r\nContent-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n$extra\r\n"
        out.write(headers.toByteArray(StandardCharsets.US_ASCII))
        if (!head) out.write(body)
        out.flush()
    }

    companion object {
        const val DEFAULT_PORT = 8765
        const val MIN_PASSWORD = 8
        private const val HTML = "text/html"

        fun isLanAddress(address: InetAddress): Boolean =
            address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress ||
                (address.address.size == 16 && (address.address[0].toInt() and 0xfe) == 0xfc)
    }
}
