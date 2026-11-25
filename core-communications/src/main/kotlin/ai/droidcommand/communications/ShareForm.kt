// Victorious Framework: turns what the owner types on the "Share files" form into a validated ShareGrant. Pure JVM so it can be tested.
package ai.droidcommand.communications

enum class ShareKind {
    FILE,
    FOLDER,
    FOLDER_WITH_SUBFOLDERS,

    /** A folder chosen with Android's folder picker; [ShareForm.path] holds its content:// tree reference. */
    TREE,
    TREE_WITH_SUBFOLDERS,

    /** One or more files chosen with Android's file picker; [ShareForm.path] holds their content:// references, one per line. */
    PICKED,
}

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
    const val MAX_PICKED = 200

    /**
     * Read-only is derived, never typed: a grant is read-only exactly when none of its operations changes storage, so the form
     * cannot produce a "read-only" grant that can write. Unsafe paths and empty choices are refused with a plain reason.
     */
    fun build(form: ShareForm, now: Long, newId: () -> String): Result<ShareGrant> {
        if (form.conversation.isBlank()) return fail("Choose the conversation this is for.")
        if (form.ops.isEmpty()) return fail("Choose at least one thing the bot may do.")
        val picked = form.kind == ShareKind.TREE || form.kind == ShareKind.TREE_WITH_SUBFOLDERS || form.kind == ShareKind.PICKED
        val path = if (picked) "" else SharePaths.normalize(form.path.trim()) ?: return fail("Enter a full path starting with /, with no “..”.")
        if (path == "/" && form.kind != ShareKind.FILE) return fail("Sharing the whole storage root is not allowed. Pick a folder.")
        val ttl = form.ttlHours
        if (ttl != null && (ttl < 1 || ttl > MAX_TTL_HOURS)) return fail("Expiry must be between 1 hour and one year, or none.")
        val recipients = form.recipients.split(',', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (recipients.size > MAX_RECIPIENTS) return fail("At most $MAX_RECIPIENTS recipients.")
        if (recipients.any { it.length > 40 }) return fail("A recipient is too long.")
        val scope: ShareScope = when (form.kind) {
            ShareKind.FILE -> ShareScope.File(path)
            ShareKind.FOLDER -> ShareScope.Folder(path, subfolders = false)
            ShareKind.FOLDER_WITH_SUBFOLDERS -> ShareScope.Folder(path, subfolders = true)
            ShareKind.TREE, ShareKind.TREE_WITH_SUBFOLDERS -> {
                val tree = ContentRefs.treeOf(form.path.trim()) ?: return fail("That is not a folder the Android folder picker returned.")
                if (ContentRefs.isRoot(tree)) return fail("Sharing a whole storage root is not allowed. Pick a folder inside it.")
                ShareScope.Tree(tree.canonical, subfolders = form.kind == ShareKind.TREE_WITH_SUBFOLDERS)
            }
            ShareKind.PICKED -> {
                val refs = form.path.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { ContentRefs.canonical(it) ?: return fail("One of the picked files is not an allowed content reference.") }.toSet()
                if (refs.isEmpty()) return fail("Pick at least one file.")
                if (refs.size > MAX_PICKED) return fail("At most $MAX_PICKED files at once.")
                if (refs.size == 1) ShareScope.Item(refs.single()) else ShareScope.Collection("picked-" + now, refs)
            }
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
