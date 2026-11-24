package ai.droidcommand.app.communications

import ai.droidcommand.communications.MediaGateway

/**
 * Victorious Framework: the honest placeholder for File-Manager's media gateway. VictorFiles' fleet bridge is still a
 * read-only stub that exposes no files, so nothing can be read and every attachment send is refused. It returns null
 * (never a fake file); replace it only when a real, owner-enabled bridge exists.
 */
object UnconnectedMediaGateway : MediaGateway {
    override fun open(normalizedPath: String): MediaGateway.Blob? = null
}
