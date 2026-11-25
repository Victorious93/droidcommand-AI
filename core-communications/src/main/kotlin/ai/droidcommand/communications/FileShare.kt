// Victorious Framework: per-conversation file-sharing permissions. Pure JVM.
//
// This is the HUB-side decision engine: it decides whether a bot, acting in ONE conversation, may touch ONE path of the
// owner's storage, for ONE operation. It does not read files. A MediaGateway (File-Manager's bridge, once it is live)
// does the reading, and only after this engine says yes. File-Manager is an EXTERNAL_NODE and gets no AIDL, no
// DependencyGuard and no shared signing key; this engine is how its data stays owner-controlled anyway.
//
// Invariants, each pinned by a test in FileShareTest:
//  1. Default deny: no grant, no access.
//  2. A grant belongs to exactly one conversation. There is no inheritance and no "any conversation" value.
//  3. Only the owner-side [FileShareEngine.grant] creates or widens a grant. Nothing a message says can.
//  4. Paths are normalised lexically and anything that could escape its folder is denied, not repaired.
//  5. Every decision, allowed or not, is written to the access history.
package ai.droidcommand.communications

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

enum class FileOp { BROWSE, SEARCH, PREVIEW, READ, AI_ANALYZE, DOWNLOAD, UPLOAD, SAVE, MODIFY, SEND }

/** Operations that change storage or send data off the device. A read-only grant can never carry these. */
val WRITING_OPS: Set<FileOp> = setOf(FileOp.UPLOAD, FileOp.SAVE, FileOp.MODIFY)

/** Where a request came from. Only [OWNER] may be the origin of a grant; [INCOMING_MESSAGE] can at most ask the owner. */
enum class Origin { OWNER, BOT, INCOMING_MESSAGE }

@Serializable
sealed class ShareScope {
    /** A folder; direct children only unless [subfolders]. */
    @Serializable
    data class Folder(val path: String, val subfolders: Boolean = false) : ShareScope()

    @Serializable
    data class File(val path: String) : ShareScope()

    /** An owner-approved set of exact files (paths or content:// references), for example a hand-picked album. */
    @Serializable
    data class Collection(val id: String, val files: Set<String>) : ShareScope()

    /** A folder the owner picked with Android's folder picker (a content:// tree reference). Direct children only unless [subfolders]. */
    @Serializable
    data class Tree(val uri: String, val subfolders: Boolean = false) : ShareScope()

    /** One file the owner picked (a content:// document reference). Matches that exact document only. */
    @Serializable
    data class Item(val uri: String) : ShareScope()
}

/** Who a grant is for. [conversation] is mandatory: it is what makes cross-conversation leakage impossible by construction. */
@Serializable
data class ShareSubject(val conversation: String, val botId: String? = null, val contact: String? = null, val account: String? = null) {
    init {
        require(conversation.isNotBlank()) { "A share subject needs a conversation." }
    }
}

@Serializable
data class ShareGrant(
    val id: String,
    val subject: ShareSubject,
    val scopes: List<ShareScope>,
    val ops: Set<FileOp>,
    val readOnly: Boolean = true,
    val requireApproval: Boolean = true,
    val expiresAtMs: Long? = null,
    /** Null means the conversation's own contact only. A set names exactly who a SEND may go to. */
    val allowedRecipients: Set<String>? = null,
    val createdAtMs: Long,
    val revoked: Boolean = false,
)

data class ShareRequest(
    val subject: ShareSubject,
    val op: FileOp,
    val path: String,
    val recipient: String? = null,
    val origin: Origin = Origin.BOT,
)

sealed class ShareDecision {
    data class Allow(val grantId: String, val normalizedPath: String) : ShareDecision()
    data class NeedsApproval(val grantId: String, val normalizedPath: String) : ShareDecision()
    data class Deny(val reason: String) : ShareDecision()
}

@Serializable
data class AccessRecord(
    val atMs: Long,
    val conversation: String,
    val botId: String?,
    val op: FileOp,
    val path: String,
    val outcome: String,
    val reason: String,
    val grantId: String?,
)

/** A bot's request for more access. It is only ever a request: the owner decides, and nothing here grants anything. */
@Serializable
data class ExpansionRequest(
    val id: String,
    val subject: ShareSubject,
    val op: FileOp,
    val path: String,
    val triggeredByIncomingMessage: Boolean,
    val createdAtMs: Long,
    val state: String = "pending",
)

interface ShareStore {
    fun load(): ShareState

    fun save(state: ShareState)
}

@Serializable
data class ShareState(val grants: List<ShareGrant> = emptyList(), val history: List<AccessRecord> = emptyList(), val requests: List<ExpansionRequest> = emptyList())

class InMemoryShareStore : ShareStore {
    private var s = ShareState()

    @Synchronized override fun load() = s

    @Synchronized override fun save(state: ShareState) {
        s = state
    }
}

/** Atomic JSON file store. Write to a temp file, then rename, so a crash never leaves half a state. */
class JsonFileShareStore(private val file: File) : ShareStore {
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized override fun load(): ShareState =
        if (!file.exists()) ShareState() else runCatching { json.decodeFromString<ShareState>(file.readText()) }.getOrElse { ShareState() }

    @Synchronized override fun save(state: ShareState) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(state))
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "Could not replace ${file.name}" }
        }
    }
}

object SharePaths {
    /**
     * Lexical normalisation. Returns null for anything that is not a plain absolute path: relative paths, `..`
     * segments, backslashes, NUL/control characters and percent-encoded separators or dots. The engine denies on null.
     * A MediaGateway must still resolve symlinks (realpath) before reading; this cannot see the filesystem.
     */
    fun normalize(raw: String): String? {
        if (raw.isEmpty() || raw.length > 1024 || !raw.startsWith("/")) return null
        if (raw.any { it == '\\' || it.code < 0x20 || it.code == 0x7f }) return null
        val lower = raw.lowercase()
        if ("%2e" in lower || "%2f" in lower || "%5c" in lower || "%00" in lower) return null
        val out = ArrayList<String>()
        for (seg in raw.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> return null
                else -> out.add(seg)
            }
        }
        return "/" + out.joinToString("/")
    }

    /** A plain absolute path, or a content:// reference in its one canonical spelling. Null for anything else. */
    fun normalizeAny(raw: String): String? = if (ContentRefs.isContent(raw)) ContentRefs.canonical(raw) else normalize(raw)

    fun within(path: String, folder: String, subfolders: Boolean): Boolean {
        if (folder == "/") return subfolders || path.count { it == '/' } == 1
        val prefix = "$folder/"
        if (!path.startsWith(prefix)) return false
        return subfolders || !path.substring(prefix.length).contains('/')
    }
}

class FileShareEngine(
    private val store: ShareStore = InMemoryShareStore(),
    private val clock: () -> Long = System::currentTimeMillis,
    /** Wire the existing KillSwitch here: while on, every file operation is denied. */
    private val emergencyStop: () -> Boolean = { false },
    private val historyLimit: Int = 5_000,
) {
    private var state = store.load()

    private fun persist() = store.save(state)

    // ---- Owner side ------------------------------------------------------------------------------------------------

    /** Owner action. Validates the grant so a malformed one is refused rather than stored. */
    @Synchronized fun grant(g: ShareGrant): Result<ShareGrant> {
        if (g.ops.isEmpty()) return Result.failure(IllegalArgumentException("A grant needs at least one operation."))
        if (g.readOnly && g.ops.any { it in WRITING_OPS }) return Result.failure(IllegalArgumentException("A read-only grant cannot carry ${g.ops.filter { it in WRITING_OPS }}."))
        if (g.scopes.isEmpty()) return Result.failure(IllegalArgumentException("A grant needs at least one folder, file or collection."))
        val scopes = g.scopes.map { sc ->
            when (sc) {
                is ShareScope.Folder -> SharePaths.normalize(sc.path)?.let { sc.copy(path = it) }
                is ShareScope.File -> SharePaths.normalize(sc.path)?.let { sc.copy(path = it) }
                is ShareScope.Collection -> {
                    val files = sc.files.map { SharePaths.normalizeAny(it) }
                    if (files.any { it == null }) null else sc.copy(files = files.filterNotNull().toSet())
                }
                is ShareScope.Tree -> ContentRefs.treeOf(sc.uri)?.takeIf { !ContentRefs.isRoot(it) }?.let { sc.copy(uri = it.canonical) }
                is ShareScope.Item -> ContentRefs.parse(sc.uri)?.takeIf { it.docId != null || it.treeId == null }?.let { sc.copy(uri = it.canonical) }
            } ?: return Result.failure(IllegalArgumentException("A scope has an unsafe path or an unsupported reference."))
        }
        val clean = g.copy(scopes = scopes)
        state = state.copy(grants = state.grants.filterNot { it.id == g.id } + clean)
        persist()
        return Result.success(clean)
    }

    @Synchronized fun revoke(id: String): Boolean {
        val g = state.grants.firstOrNull { it.id == id && !it.revoked } ?: return false
        state = state.copy(grants = state.grants.map { if (it.id == id) g.copy(revoked = true) else it })
        persist()
        return true
    }

    /** Immediate: after this returns, no grant for the conversation answers yes. */
    @Synchronized fun revokeConversation(conversation: String): Int {
        val hit = state.grants.filter { it.subject.conversation == conversation && !it.revoked }
        if (hit.isEmpty()) return 0
        state = state.copy(grants = state.grants.map { if (it.subject.conversation == conversation) it.copy(revoked = true) else it })
        persist()
        return hit.size
    }

    @Synchronized fun grants(): List<ShareGrant> = state.grants

    @Synchronized fun history(conversation: String? = null): List<AccessRecord> = state.history.filter { conversation == null || it.conversation == conversation }

    @Synchronized fun pendingRequests(): List<ExpansionRequest> = state.requests.filter { it.state == "pending" }

    /**
     * Owner action: turns a pending request into a real grant the owner defined. The request itself carries no
     * authority; the owner supplies the grant, which may be narrower than what was asked.
     */
    @Synchronized fun approveRequest(requestId: String, grant: ShareGrant): Result<ShareGrant> {
        val req = state.requests.firstOrNull { it.id == requestId && it.state == "pending" }
            ?: return Result.failure(IllegalArgumentException("No such pending request."))
        if (grant.subject != req.subject) return Result.failure(IllegalArgumentException("The grant must be for the conversation that asked."))
        val r = grant(grant)
        if (r.isSuccess) {
            state = state.copy(requests = state.requests.map { if (it.id == requestId) it.copy(state = "approved") else it })
            persist()
        }
        return r
    }

    @Synchronized fun denyRequest(requestId: String): Boolean {
        if (state.requests.none { it.id == requestId && it.state == "pending" }) return false
        state = state.copy(requests = state.requests.map { if (it.id == requestId) it.copy(state = "denied") else it })
        persist()
        return true
    }

    // ---- Bot side --------------------------------------------------------------------------------------------------

    /** Records a request for more access. Does not widen anything. Duplicate pending requests are collapsed. */
    @Synchronized fun requestExpansion(req: ShareRequest): ExpansionRequest {
        val path = SharePaths.normalizeAny(req.path) ?: req.path.take(200)
        state.requests.firstOrNull { it.state == "pending" && it.subject == req.subject && it.op == req.op && it.path == path }?.let { return it }
        val item = ExpansionRequest(
            "x" + (state.requests.size + 1) + "-" + clock(), req.subject, req.op, path,
            triggeredByIncomingMessage = req.origin == Origin.INCOMING_MESSAGE, createdAtMs = clock(),
        )
        state = state.copy(requests = (state.requests + item).takeLast(500))
        persist()
        return item
    }

    @Synchronized fun decide(req: ShareRequest): ShareDecision {
        val d = evaluate(req)
        val path = SharePaths.normalizeAny(req.path) ?: "(rejected path)"
        val (outcome, reason, gid) = when (d) {
            is ShareDecision.Allow -> Triple("allow", "granted", d.grantId)
            is ShareDecision.NeedsApproval -> Triple("needs_approval", "owner approval required", d.grantId)
            is ShareDecision.Deny -> Triple("deny", d.reason, null)
        }
        val rec = AccessRecord(clock(), req.subject.conversation, req.subject.botId, req.op, path, outcome, reason, gid)
        state = state.copy(history = (state.history + rec).takeLast(historyLimit))
        persist()
        return d
    }

    private fun evaluate(req: ShareRequest): ShareDecision {
        if (emergencyStop()) return ShareDecision.Deny("Emergency stop is on.")
        if (req.origin == Origin.INCOMING_MESSAGE) return ShareDecision.Deny("A received message cannot authorise file access.")
        val path = SharePaths.normalizeAny(req.path) ?: return ShareDecision.Deny("The path is not a plain absolute path or an allowed content reference.")
        val now = clock()
        val candidates = state.grants.filter { g ->
            !g.revoked && (g.expiresAtMs == null || now < g.expiresAtMs) && subjectCovers(g.subject, req.subject) && req.op in g.ops &&
                !(g.readOnly && req.op in WRITING_OPS) && g.scopes.any { covers(it, path) }
        }
        if (candidates.isEmpty()) return ShareDecision.Deny("No grant covers this conversation, operation and path.")
        // The recipient rule is part of what a grant covers, so it filters candidates rather than judging only the first.
        val usable = if (req.op == FileOp.SEND) candidates.filter { recipientOk(it, req) } else candidates
        if (usable.isEmpty()) return ShareDecision.Deny("The recipient is not one this grant allows.")
        val g = usable.firstOrNull { !it.requireApproval } ?: usable.first()
        return if (g.requireApproval) ShareDecision.NeedsApproval(g.id, path) else ShareDecision.Allow(g.id, path)
    }

    /** The grant's subject must match every field it sets, and always the same conversation. */
    private fun subjectCovers(grant: ShareSubject, asking: ShareSubject) =
        grant.conversation == asking.conversation &&
            (grant.botId == null || grant.botId == asking.botId) &&
            (grant.contact == null || grant.contact == asking.contact) &&
            (grant.account == null || grant.account == asking.account)

    private fun recipientOk(g: ShareGrant, req: ShareRequest): Boolean {
        val to = req.recipient ?: return false
        val allowed = g.allowedRecipients ?: setOfNotNull(req.subject.contact)
        return to in allowed
    }

    private fun covers(scope: ShareScope, path: String) = when (scope) {
        is ShareScope.Folder -> path == scope.path || SharePaths.within(path, scope.path, scope.subfolders)
        is ShareScope.File -> scope.path == path
        is ShareScope.Collection -> path in scope.files
        is ShareScope.Tree -> ContentRefs.parse(path)?.let { req -> ContentRefs.parse(scope.uri)?.let { tree -> ContentRefs.within(req, tree, scope.subfolders) } } ?: false
        is ShareScope.Item -> path == scope.uri
    }
}
