// Victorious Framework: attachment validation and the permission-gated send path. Pure JVM.
package ai.droidcommand.communications

enum class MediaKind { IMAGE, VIDEO, AUDIO, DOCUMENT, TEXT }

data class Sniffed(val mime: String, val kind: MediaKind)

/** What one provider route will carry. Defaults are CONSERVATIVE ASSUMPTIONS, not verified carrier limits; the owner can change them. */
data class MediaLimits(val maxBytes: Long, val allowed: Set<String>, val note: String = "")

object ProviderMedia {
    private val COMMON = setOf("image/jpeg", "image/png", "image/gif", "image/webp", "video/mp4", "video/3gpp", "audio/mpeg", "audio/mp4", "audio/amr", "audio/ogg", "application/pdf", "text/plain")

    /** Native SMS carries no attachments; MMS is size-limited by the carrier (1 MB is a cautious default); TextNow has no supported automated path. */
    fun limits(route: String): MediaLimits? = when (route.lowercase()) {
        "mms" -> MediaLimits(1_000_000, COMMON, "Assumed carrier limit; verify with your carrier.")
        "email" -> MediaLimits(20_000_000, COMMON + "application/zip")
        else -> null // sms, textnow and unknown routes: no automated attachment sending is claimed
    }
}

sealed class AttachmentCheck {
    data class Ok(val mime: String, val kind: MediaKind, val safeName: String) : AttachmentCheck()
    data class Rejected(val reasons: List<String>) : AttachmentCheck()
}

object AttachmentValidator {
    private val BLOCKED_EXT = setOf("apk", "dex", "exe", "dll", "so", "bat", "cmd", "sh", "js", "jar", "msi", "scr", "vbs", "ps1", "html", "htm", "svg", "xapk", "aab")

    fun sniff(h: ByteArray): Sniffed? {
        fun at(i: Int, s: String) = h.size >= i + s.length && s.indices.all { h[i + it] == s[it].code.toByte() }
        fun b(i: Int) = if (h.size > i) h[i].toInt() and 0xff else -1
        return when {
            b(0) == 0xFF && b(1) == 0xD8 && b(2) == 0xFF -> Sniffed("image/jpeg", MediaKind.IMAGE)
            b(0) == 0x89 && at(1, "PNG") && b(4) == 0x0D -> Sniffed("image/png", MediaKind.IMAGE)
            at(0, "GIF87a") || at(0, "GIF89a") -> Sniffed("image/gif", MediaKind.IMAGE)
            at(0, "RIFF") && at(8, "WEBP") -> Sniffed("image/webp", MediaKind.IMAGE)
            at(0, "RIFF") && at(8, "WAVE") -> Sniffed("audio/wav", MediaKind.AUDIO)
            at(4, "ftyp") -> when {
                at(8, "3gp") -> Sniffed("video/3gpp", MediaKind.VIDEO)
                at(8, "M4A") -> Sniffed("audio/mp4", MediaKind.AUDIO)
                else -> Sniffed("video/mp4", MediaKind.VIDEO)
            }
            at(0, "ID3") || (b(0) == 0xFF && (b(1) and 0xE0) == 0xE0) -> Sniffed("audio/mpeg", MediaKind.AUDIO)
            at(0, "OggS") -> Sniffed("audio/ogg", MediaKind.AUDIO)
            at(0, "#!AMR") -> Sniffed("audio/amr", MediaKind.AUDIO)
            at(0, "%PDF-") -> Sniffed("application/pdf", MediaKind.DOCUMENT)
            at(0, "PK") && b(2) == 3 && b(3) == 4 -> Sniffed("application/zip", MediaKind.DOCUMENT)
            looksExecutable(h) -> null
            h.isNotEmpty() && h.none { it.toInt() == 0 } && isUtf8(h) -> Sniffed("text/plain", MediaKind.TEXT)
            else -> null
        }
    }

    private fun looksExecutable(h: ByteArray): Boolean {
        fun at(i: Int, s: String) = h.size >= i + s.length && s.indices.all { h[i + it] == s[it].code.toByte() }
        return (h.isNotEmpty() && h[0].toInt() == 0x7f && at(1, "ELF")) || at(0, "MZ") || at(0, "dex\n") || at(0, "#!")
    }

    private fun isUtf8(h: ByteArray) = runCatching {
        Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(h))
        true
    }.getOrDefault(false)

    fun safeName(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = base.filter { it.code >= 0x20 && it.code != 0x7f && it !in "<>:\"|?*" }.trimStart('.').trim().take(100)
        return cleaned.ifEmpty { "attachment" }
    }

    fun validate(fileName: String, declaredMime: String?, head: ByteArray, size: Long, limits: MediaLimits?): AttachmentCheck {
        val why = ArrayList<String>()
        if (limits == null) return AttachmentCheck.Rejected(listOf("This route has no supported automated attachment path."))
        if (size <= 0) why.add("The file is empty.")
        if (size > limits.maxBytes) why.add("The file is $size bytes; this route allows ${limits.maxBytes}.")
        val parts = fileName.lowercase().split('.').drop(1)
        if (parts.any { it in BLOCKED_EXT }) why.add("The file name has a blocked extension.")
        val sniffed = sniff(head)
        if (sniffed == null) {
            why.add("The content is not a recognised, allowed file type.")
        } else {
            if (sniffed.mime !in limits.allowed) why.add("${sniffed.mime} is not allowed on this route.")
            val d = declaredMime?.lowercase()
            if (d != null && d != "application/octet-stream" && d != sniffed.mime) why.add("Declared type $d does not match the content (${sniffed.mime}).")
        }
        return if (why.isEmpty() && sniffed != null) AttachmentCheck.Ok(sniffed.mime, sniffed.kind, safeName(fileName)) else AttachmentCheck.Rejected(why)
    }
}

/** Reads bytes for a path the engine already approved. File-Manager's bridge implements this; nothing here touches storage. */
interface MediaGateway {
    class Blob(val name: String, val declaredMime: String?, val size: Long, val head: ByteArray)

    /** Null when the file cannot be read. Implementations MUST resolve symlinks and refuse anything outside the approved path. */
    fun open(normalizedPath: String): Blob?
}

/**
 * Wraps a backend so that `send_attachment` and `send_mms` can only carry a file the owner shared with THIS conversation,
 * to a recipient that grant allows, and only after the bytes pass validation. Everything else passes through unchanged.
 * `attachment_ref` is the file path. Per-call owner approval still applies in the broker; this adds the file-level check.
 */
class ShareGatedBackend(
    private val inner: CommsBackend,
    private val shares: FileShareEngine,
    private val gateway: MediaGateway,
    private val routeOf: (Map<String, String>) -> String = { if (it["provider"].isNullOrBlank()) "mms" else it["provider"]!!.lowercase() },
    private val contactOf: (conversation: String) -> String? = { null },
) : CommsBackend by inner {
    override fun call(capability: String, args: Map<String, String>, ctx: BackendContext): BackendResult {
        if (capability != "communications.send_attachment" && capability != "communications.send_mms") return inner.call(capability, args, ctx)
        val conversation = args["conversation"]
            ?: return BackendResult.Refused("share_needs_conversation", "Files can only be shared into a named conversation, not to a bare number.")
        val contact = contactOf(conversation)
        val ref = args["attachment_ref"] ?: return BackendResult.Refused("bad_arguments", "attachment_ref is required.")
        val subject = ShareSubject(conversation, ctx.botId, contact)
        // `autonomy == "auto"` means no human tapped for this call, so a grant that requires approval cannot be satisfied here.
        val decision = shares.decide(ShareRequest(subject, FileOp.SEND, ref, recipient = contact, origin = Origin.BOT))
        val path = when (decision) {
            is ShareDecision.Deny -> {
                shares.requestExpansion(ShareRequest(subject, FileOp.SEND, ref, contact))
                return BackendResult.Refused("file_not_shared", "The owner has not shared that file with this conversation. A request was queued for the owner.")
            }
            is ShareDecision.NeedsApproval -> if (ctx.autonomy == "approved") {
                decision.normalizedPath
            } else {
                return BackendResult.Refused("file_share_needs_approval", "Sharing this file needs the owner's approval for this send.")
            }
            is ShareDecision.Allow -> decision.normalizedPath
        }
        val blob = gateway.open(path) ?: return BackendResult.Failed("The shared file could not be read.")
        return when (val v = AttachmentValidator.validate(blob.name, blob.declaredMime, blob.head, blob.size, ProviderMedia.limits(routeOf(args)))) {
            is AttachmentCheck.Rejected -> BackendResult.Refused("attachment_rejected", v.reasons.joinToString(" "))
            is AttachmentCheck.Ok -> inner.call(capability, args + ("attachment_ref" to path), ctx)
        }
    }
}
