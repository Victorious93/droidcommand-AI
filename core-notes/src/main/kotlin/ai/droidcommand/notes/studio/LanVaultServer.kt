package ai.droidcommand.notes.studio

import ai.droidcommand.notes.NoteVault
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64
import kotlin.concurrent.thread

/** Rules for the optional LAN viewer, separate from sockets so they are easy to test. */
object LanVaultPolicy {
    const val MIN_PASSWORD_LENGTH = 8
    const val DEFAULT_PORT = 8787
    const val DEFAULT_ENABLED = false
    const val USER = "vault"

    /** Loopback, RFC 1918 private, and link-local IPv4/IPv6 only. Never a public address. */
    fun isLanAddress(address: InetAddress): Boolean =
        address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress ||
            (address !is Inet4Address && (address.address[0].toInt() and 0xFE) == 0xFC)

    fun passwordProblem(password: String): String? = when {
        password.length < MIN_PASSWORD_LENGTH -> "Use at least $MIN_PASSWORD_LENGTH characters."
        password.isBlank() -> "Password can't be blank."
        else -> null
    }

    fun checkAuth(header: String?, password: String): Boolean {
        if (header == null || !header.startsWith("Basic ")) return false
        val decoded = runCatching { String(Base64.getDecoder().decode(header.removePrefix("Basic ").trim()), Charsets.UTF_8) }.getOrNull()
            ?: return false
        val given = decoded.substringAfter(':', "")
        return MessageDigest.isEqual(given.toByteArray(Charsets.UTF_8), password.toByteArray(Charsets.UTF_8))
    }
}

/**
 * Read-only HTTP viewer for the vault. Binds to one LAN address the caller
 * picks (never 0.0.0.0), refuses non-LAN peers, requires HTTP Basic auth on
 * every request, and answers only GET. There is no write route.
 */
class LanVaultServer(
    private val vault: () -> NoteVault,
    private val password: String,
    private val mermaidJs: () -> ByteArray? = { null },
) {
    private var socket: ServerSocket? = null

    val running: Boolean get() = socket?.isClosed == false
    val port: Int get() = socket?.localPort ?: -1

    fun start(bind: InetAddress, port: Int = LanVaultPolicy.DEFAULT_PORT) {
        require(LanVaultPolicy.passwordProblem(password) == null) { "Password too short" }
        require(LanVaultPolicy.isLanAddress(bind) && !bind.isAnyLocalAddress) { "Only a LAN address can be used" }
        stop()
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress(bind, port))
        socket = server
        thread(name = "lan-vault", isDaemon = true) {
            while (!server.isClosed) {
                val client = try {
                    server.accept()
                } catch (_: SocketException) {
                    break
                }
                thread(isDaemon = true) { client.use { handle(it) } }
            }
        }
    }

    fun stop() {
        socket?.close()
        socket = null
    }

    internal fun handle(client: Socket) {
        client.soTimeout = 10_000
        val out = client.getOutputStream()
        if (!LanVaultPolicy.isLanAddress(client.inetAddress)) return respond(out, 403, "text/plain", "LAN only")
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
        val requestLine = reader.readLine() ?: return
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val parts = requestLine.split(' ')
        if (parts.size < 2) return respond(out, 400, "text/plain", "Bad request")
        if (parts[0] != "GET") return respond(out, 405, "text/plain", "Read-only")
        if (!LanVaultPolicy.checkAuth(headers["authorization"], password)) {
            return respond(out, 401, "text/plain", "Password required", "WWW-Authenticate: Basic realm=\"Brain vault\"\r\n")
        }
        if (parts[1] == "/mermaid.js") {
            val js = mermaidJs() ?: return respond(out, 404, "text/plain", "Not found")
            return respondBytes(out, 200, "application/javascript", js)
        }
        val (status, type, body) = route(parts[1])
        respond(out, status, type, body)
    }

    /** Pure routing for tests: (status, content type, body). */
    fun route(target: String): Triple<Int, String, String> {
        val path = URLDecoder.decode(target.substringBefore('?'), "UTF-8")
        val v = vault()
        return try {
            when {
                path == "/" || path.isEmpty() -> Triple(200, HTML, index(v))
                path.startsWith("/note/") -> {
                    val rel = path.removePrefix("/note/")
                    val md = v.read(rel)
                    Triple(200, HTML, wrap(rel, MermaidPreview.page(md, scriptUrl = "/mermaid.js", scriptSource = "'self'")))
                }
                path.startsWith("/raw/") -> Triple(200, "text/plain; charset=utf-8", v.read(path.removePrefix("/raw/")))
                else -> Triple(404, "text/plain", "Not found")
            }
        } catch (e: IllegalArgumentException) {
            Triple(400, "text/plain", "Bad path")
        } catch (e: java.io.IOException) {
            Triple(404, "text/plain", "Not found")
        }
    }

    private fun wrap(title: String, page: String) = page.replace("<body>", "<body><p><a href=\"/\">All notes</a> · ${MermaidPreview.escape(title)}</p>")

    private fun index(v: NoteVault): String {
        val links = v.listAllMarkdown().sorted().joinToString("") { p ->
            val e = MermaidPreview.escape(p)
            "<li><a href=\"/note/${java.net.URLEncoder.encode(p, "UTF-8").replace("%2F", "/").replace("+", "%20")}\">$e</a></li>"
        }
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>Brain vault</title></head>" +
            "<body><h1>Brain vault (read-only)</h1><ul>$links</ul></body></html>"
    }

    private fun respond(out: OutputStream, status: Int, type: String, body: String, extra: String = "") =
        respondBytes(out, status, type, body.toByteArray(Charsets.UTF_8), extra)

    private fun respondBytes(out: OutputStream, status: Int, type: String, bytes: ByteArray, extra: String = "") {
        val head = "HTTP/1.1 $status ${reason(status)}\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n$extra\r\n"
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    private fun reason(s: Int) = when (s) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        else -> "Error"
    }

    companion object {
        private const val HTML = "text/html; charset=utf-8"
    }
}
