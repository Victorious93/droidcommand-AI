package ai.droidcommand.security

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.format.DateTimeParseException

class InvalidGrantId(id: String) : IllegalArgumentException(
    "Grant id '$id' must match ${JsonFileGrantStore.ID_PATTERN.pattern} (no path separators or traversal)",
)

@Serializable
private data class GrantRecordDto(
    val id: String,
    val capability: String,
    @SerialName("issued_at") val issuedAt: String,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("single_use") val singleUse: Boolean,
    val consumed: Boolean = false,
    val revoked: Boolean = false,
)

/**
 * A real, file-backed [GrantStore]: each grant is one JSON file under
 * [directory], surviving a process restart — extending the same
 * `JsonFileMacroStore`/`JsonFileConversationStore`/`JsonFileKnowledgeStore`
 * pattern (`core-agent`) to ROADMAP-048's grant-lifecycle gap, now that its
 * prerequisite (a real persistence pattern) exists.
 *
 * Unlike those stores, a grant's record isn't immutable data a caller
 * merely saves and loads — [consume]/[revoke] mutate its lifecycle state,
 * and that mutation must itself survive a restart or persistence here would
 * be security theater (a revoked grant reverting to live, or a single-use
 * grant becoming reusable, the moment the process restarts). So
 * [consumed]/[revoked] are stored as fields on the same record file, and
 * [consume]/[revoke] are read-modify-write operations on it rather than a
 * separate in-memory set the way [InMemoryGrantStore] uses.
 *
 * [Grant.id] must match [ID_PATTERN]; the resolved file path is then
 * re-checked to stay inside [directory] before any read/write — the
 * identical fail-closed, normalize-then-`startsWith` pattern
 * `JsonFileKnowledgeStore` already uses. [issue] fails closed at [capacity]
 * exactly like [InMemoryGrantStore], counting existing grant files rather
 * than trusting an in-process counter, so capacity is enforced correctly
 * even across a restart. As there, only live grants use up capacity (spent,
 * revoked and expired records stay on disk but are not counted, and a record
 * that cannot be read counts as live), and [issue] never overwrites a record:
 * a revoked or spent grant cannot be revived by issuing its id again.
 */
class JsonFileGrantStore(private val directory: Path, private val capacity: Int = 10_000) : GrantStore {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val lock = Any()

    init {
        Files.createDirectories(directory)
    }

    override fun issue(grant: Grant): Boolean = synchronized(lock) {
        if (count() >= capacity && liveCount(Instant.now()) >= capacity) return false
        val record = GrantRecordDto(
            id = grant.id,
            capability = grant.capability,
            issuedAt = grant.issuedAt.toString(),
            expiresAt = grant.expiresAt?.toString(),
            singleUse = grant.singleUse,
        )
        try {
            write(record, createOnly = true)
        } catch (e: FileAlreadyExistsException) {
            return false
        }
        true
    }

    override fun check(id: String?, capability: String, now: Instant): GrantCheck {
        if (id == null) return GrantCheck.Denied("no grant id supplied for capability '$capability'")
        val record = read(id) ?: return GrantCheck.Denied("no such grant '$id'")
        if (record.capability != capability) {
            return GrantCheck.Denied("grant '$id' is for capability '${record.capability}', not '$capability'")
        }
        if (record.revoked) return GrantCheck.Denied("grant '$id' has been revoked")
        if (record.singleUse && record.consumed) {
            return GrantCheck.Denied("grant '$id' is single-use and has already been consumed")
        }
        val expiresAt = record.expiresAt?.let(::parseInstant)
        if (expiresAt != null && now.isAfter(expiresAt)) {
            return GrantCheck.Denied("grant '$id' expired at $expiresAt")
        }
        return GrantCheck.Live
    }

    override fun consume(id: String) {
        synchronized(lock) {
            val record = read(id) ?: return
            write(record.copy(consumed = true))
        }
    }

    override fun revoke(id: String) {
        synchronized(lock) {
            val record = read(id) ?: return
            write(record.copy(revoked = true))
        }
    }

    private fun count(): Int {
        if (!Files.isDirectory(directory)) return 0
        Files.newDirectoryStream(directory, "*$EXTENSION").use { stream -> return stream.count() }
    }

    /** Grants whose record says they can still be used: not revoked, not a spent single-use grant, not expired. */
    private fun liveCount(now: Instant): Int {
        if (!Files.isDirectory(directory)) return 0
        Files.newDirectoryStream(directory, "*$EXTENSION").use { stream -> return stream.count { isLive(it, now) } }
    }

    /** A record that cannot be read counts as live: corruption must never free up capacity. */
    private fun isLive(file: Path, now: Instant): Boolean = try {
        val record = json.decodeFromString(GrantRecordDto.serializer(), Files.readString(file))
        val expiresAt = record.expiresAt?.let(::parseInstant)
        !record.revoked && !(record.singleUse && record.consumed) && (expiresAt == null || !now.isAfter(expiresAt))
    } catch (e: IOException) {
        true
    } catch (e: SerializationException) {
        true
    }

    /** With [createOnly] the write fails with [FileAlreadyExistsException] instead of replacing an existing record. */
    private fun write(record: GrantRecordDto, createOnly: Boolean = false) {
        val file = fileFor(record.id)
        val bytes = json.encodeToString(GrantRecordDto.serializer(), record).toByteArray()
        if (createOnly) {
            // CREATE_NEW is atomic, so of two racing issuers of one id exactly one wins.
            Files.write(file, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        } else {
            Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
        }
    }

    private fun read(id: String): GrantRecordDto? {
        // An id outside ID_PATTERN can never have been issued, so it is unknown rather than an error: check, consume
        // and revoke take ids from callers, and only issue (which goes through fileFor) rejects a malformed one.
        if (!ID_PATTERN.matches(id)) return null
        val file = fileFor(id)
        if (!Files.isRegularFile(file)) return null
        return try {
            json.decodeFromString(GrantRecordDto.serializer(), Files.readString(file))
        } catch (e: SerializationException) {
            throw IOException("Grant record file for '$id' is not valid JSON", e)
        }
    }

    private fun parseInstant(text: String): Instant = try {
        Instant.parse(text)
    } catch (e: DateTimeParseException) {
        throw IOException("Grant record has an unparseable timestamp: $text", e)
    }

    private fun fileFor(id: String): Path {
        if (!ID_PATTERN.matches(id)) throw InvalidGrantId(id)
        val normalizedRoot = directory.normalize()
        val candidate = normalizedRoot.resolve("$id$EXTENSION").normalize()
        if (!candidate.startsWith(normalizedRoot)) throw InvalidGrantId(id)
        return candidate
    }

    companion object {
        val ID_PATTERN = Regex("[A-Za-z0-9_-]+")
        private const val EXTENSION = ".grant.json"
    }
}
