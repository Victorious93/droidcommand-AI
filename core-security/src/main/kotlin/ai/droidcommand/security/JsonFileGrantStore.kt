package ai.droidcommand.security

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
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
 * even across a restart.
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
        if (count() >= capacity) return false
        write(
            GrantRecordDto(
                id = grant.id,
                capability = grant.capability,
                issuedAt = grant.issuedAt.toString(),
                expiresAt = grant.expiresAt?.toString(),
                singleUse = grant.singleUse,
            ),
        )
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

    private fun write(record: GrantRecordDto) {
        val file = fileFor(record.id)
        val bytes = json.encodeToString(GrantRecordDto.serializer(), record).toByteArray()
        Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
    }

    private fun read(id: String): GrantRecordDto? {
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
