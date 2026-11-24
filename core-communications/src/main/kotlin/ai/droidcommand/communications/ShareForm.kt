// Victorious Framework: turns what the owner types on the "Share files" form into a validated ShareGrant. Pure JVM so it can be tested.
package ai.droidcommand.communications

enum class ShareKind { FILE, FOLDER, FOLDER_WITH_SUBFOLDERS }

data class ShareForm(
    val conversation: String,
    val botId: String = "",
    val kind: ShareKind = ShareKind.FOLDER,
    val path: String = "",
    val ops: Set<FileOp> = emptySet(),
    /** Null means no expiry (permanent until revoked). */
    val ttlHours: Long? = 24,
    /** Comma, space or newline separated. Blank means: only the conversation's own contact. */
    val recipients: String = "",
    val requireApproval: Boolean = true,
)

object ShareForms {
    const val MAX_TTL_HOURS = 24L * 365
    const val MAX_RECIPIENTS = 20

    /**
     * Read-only is derived, never typed: a grant is read-only exactly when none of its operations changes storage, so the form
     * cannot produce a "read-only" grant that can write. Unsafe paths and empty choices are refused with a plain reason.
     */
    fun build(form: ShareForm, now: Long, newId: () -> String): Result<ShareGrant> {
        if (form.conversation.isBlank()) return fail("Choose the conversation this is for.")
        if (form.ops.isEmpty()) return fail("Choose at least one thing the bot may do.")
        val path = SharePaths.normalize(form.path.trim()) ?: return fail("Enter a full path starting with /, with no “..”.")
        if (path == "/" && form.kind != ShareKind.FILE) return fail("Sharing the whole storage root is not allowed. Pick a folder.")
        val ttl = form.ttlHours
        if (ttl != null && (ttl < 1 || ttl > MAX_TTL_HOURS)) return fail("Expiry must be between 1 hour and one year, or none.")
        val recipients = form.recipients.split(',', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (recipients.size > MAX_RECIPIENTS) return fail("At most $MAX_RECIPIENTS recipients.")
        if (recipients.any { it.length > 40 }) return fail("A recipient is too long.")
        val scope = when (form.kind) {
            ShareKind.FILE -> ShareScope.File(path)
            ShareKind.FOLDER -> ShareScope.Folder(path, subfolders = false)
            ShareKind.FOLDER_WITH_SUBFOLDERS -> ShareScope.Folder(path, subfolders = true)
        }
        val grant = ShareGrant(
            id = newId(),
            subject = ShareSubject(form.conversation.trim(), form.botId.trim().ifEmpty { null }),
            scopes = listOf(scope),
            ops = form.ops,
            readOnly = form.ops.none { it in WRITING_OPS },
            requireApproval = form.requireApproval,
            expiresAtMs = ttl?.let { now + it * 3_600_000L },
            allowedRecipients = recipients.takeIf { it.isNotEmpty() },
            createdAtMs = now,
        )
        return Result.success(grant)
    }

    private fun fail(m: String) = Result.failure<ShareGrant>(IllegalArgumentException(m))
}
