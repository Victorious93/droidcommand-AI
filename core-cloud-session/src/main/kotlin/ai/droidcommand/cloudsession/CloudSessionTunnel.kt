package ai.droidcommand.cloudsession

/**
 * One cloud coding tunnel. [open] starts a session and records its plan. [advance] walks the same
 * session from plan to edit, then to report, then to done. [cancel] stops a session that has not
 * finished. [list] is newest first.
 *
 * The session model is real. Whether [transport] talks to a live backend is the transport's own
 * status — the stub transport does not.
 */
interface CloudSessionTunnel {
    val transport: CloudSessionTransport

    val backendStatus: String

    fun list(): List<CloudCodingSession>

    fun open(goal: String): CloudCodingSession

    fun advance(id: String): CloudCodingSession

    fun cancel(id: String): CloudCodingSession

    fun get(id: String): CloudCodingSession?
}
