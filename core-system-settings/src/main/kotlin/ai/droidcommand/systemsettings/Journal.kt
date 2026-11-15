package ai.droidcommand.systemsettings

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** Who asked for a change. Each source has its own rules in [SystemSettingsEngine]. */
enum class RequestSource { UI, AI, REMOTE, BOOT_REPLAY }

enum class OperationKind { APPLY, RESTORE }

enum class OperationStatus {
    /** Recorded, and being applied. A record still in this state after a restart was interrupted. */
    APPLYING,
    COMPLETED,

    /** A write failed and every earlier write was put back. */
    FAILED_ROLLED_BACK,

    /** A write failed and at least one earlier write could not be put back; the record says which. */
    FAILED_PARTIAL,

    /** The process stopped mid-way; [SystemSettingsEngine.recoverInterrupted] read the phone and recorded what it found. Nothing was replayed. */
    INTERRUPTED,
}

enum class EntryPhase { PENDING, WRITE_STARTED, WRITTEN, DONE }

/** What happened to one entry. Only the last three mean the value is stored. */
enum class EntryOutcome {
    NOT_ATTEMPTED,
    PERMISSION_DENIED,
    REJECTED_VALUE,
    READBACK_MISMATCH,
    BACKEND_UNAVAILABLE,
    TIMEOUT,
    ERROR,

    /** Stored and read back, but nothing proves the phone behaves differently. */
    STORED_EFFECT_UNVERIFIED,

    /** Stored, read back, and an independent check observed the effect. */
    EFFECT_VERIFIED,

    /** Restore only: the entry was already at the target, so nothing was written. */
    ALREADY_AT_TARGET,

    /** After a restart the value on the phone equals the proposed one; the write was not seen to finish. */
    UNCONFIRMED_AFTER_INTERRUPT,
    ;

    val stored: Boolean get() = this == STORED_EFFECT_UNVERIFIED || this == EFFECT_VERIFIED || this == UNCONFIRMED_AFTER_INTERRUPT
    val ok: Boolean get() = stored || this == ALREADY_AT_TARGET
}

enum class RestoreStatus {
    NOT_RESTORED,
    RESTORED,
    AUTO_ROLLED_BACK,

    /** The value changed since this operation wrote it, so it was left alone. */
    CONFLICT,
    RESTORE_FAILED,
}

data class JournalEntry(
    val key: SettingKey,
    /** The exact state before this operation, captured before any write. */
    val previous: Snapshot,
    val proposed: Proposed,
    val phase: EntryPhase = EntryPhase.PENDING,
    val outcome: EntryOutcome = EntryOutcome.NOT_ATTEMPTED,
    val note: String = "",
    /** What a read after the write returned. */
    val observedAfterWrite: Snapshot? = null,
    val effectNote: String? = null,
    val restoreStatus: RestoreStatus = RestoreStatus.NOT_RESTORED,
    val restoreNote: String = "",
    val restoredAt: Long? = null,
    /** True when [previous] became the original baseline of this entry. */
    val startsBaseline: Boolean = false,
    /** True when this entry puts the original baseline back (restore to baseline). */
    val clearsBaseline: Boolean = false,
    /** Restore operations: the entry of an earlier operation that this restores. */
    val restoresOperationId: String? = null,
)

data class Operation(
    val id: String,
    val kind: OperationKind,
    val source: RequestSource,
    val label: String,
    val createdAt: Long,
    val backend: AccessKind,
    val deviceFingerprint: String,
    val status: OperationStatus,
    val entries: List<JournalEntry>,
    val profileId: String? = null,
    val restoresOperationId: String? = null,
    val finishedAt: Long? = null,
    val note: String = "",
)

/** The original state of one entry, from before the first change this app recorded for it. */
data class Baseline(val key: SettingKey, val snapshot: Snapshot, val operationId: String, val recordedAt: Long)

object Baselines {
    /** The baselines that are still active: recorded at the first change and cleared only by a verified restore to baseline. */
    fun compute(operations: List<Operation>): Map<String, Baseline> {
        val out = LinkedHashMap<String, Baseline>()
        for (op in operations.sortedBy { it.createdAt }) {
            for (e in op.entries) {
                if (e.startsBaseline) out[e.key.id] = Baseline(e.key, e.previous, op.id, op.createdAt)
                if (e.clearsBaseline && e.outcome.ok && e.phase != EntryPhase.PENDING) out.remove(e.key.id)
            }
        }
        return out
    }

    /** What the journal believes [keyId] holds now, or null when this app never changed it. Used to detect that something else changed it since. */
    fun lastKnown(operations: List<Operation>, keyId: String): Snapshot? {
        for (op in operations.sortedByDescending { it.createdAt }) {
            for (e in op.entries.asReversed()) {
                if (e.key.id != keyId) continue
                when {
                    e.restoreStatus == RestoreStatus.RESTORED || e.restoreStatus == RestoreStatus.AUTO_ROLLED_BACK -> return e.previous
                    e.outcome.stored -> return e.observedAfterWrite ?: e.proposed.asSnapshot()
                    e.outcome == EntryOutcome.READBACK_MISMATCH -> return e.observedAfterWrite ?: continue
                    e.outcome == EntryOutcome.ALREADY_AT_TARGET -> return e.proposed.asSnapshot()
                    else -> Unit
                }
            }
        }
        return null
    }
}

interface OperationJournal {
    /** Durable on return, or throws. The engine refuses to change anything it cannot record first. */
    @Throws(IOException::class)
    fun save(operation: Operation)

    fun load(id: String): Operation?

    /** Oldest first. */
    fun all(): List<Operation>

    /** Files that could not be read last time [all] ran. */
    fun unreadable(): List<String>
}

/** For tests and tools that do not need a file. [failSaves] makes every save throw, to prove nothing is written without a record. */
class InMemoryOperationJournal : OperationJournal {
    private val ops = LinkedHashMap<String, Operation>()
    var failSaves = false

    @Synchronized
    override fun save(operation: Operation) {
        if (failSaves) throw IOException("journal unavailable")
        ops[operation.id] = operation
    }

    @Synchronized
    override fun load(id: String): Operation? = ops[id]

    @Synchronized
    override fun all(): List<Operation> = ops.values.sortedBy { it.createdAt }

    override fun unreadable(): List<String> = emptyList()
}

/** One JSON file per operation in [directory]. A save writes a temp file, forces it to disk and renames it over the target, so a crash leaves either the old or the new record, never half of one. */
class FileOperationJournal(private val directory: Path) : OperationJournal {
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var unreadableFiles: List<String> = emptyList()

    init {
        Files.createDirectories(directory)
    }

    @Synchronized
    override fun save(operation: Operation) {
        val target = fileFor(operation.id)
        val tmp = directory.resolve(".tmp-${operation.id}")
        val bytes = json.encodeToString(OperationDto.serializer(), operation.toDto()).toByteArray(StandardCharsets.UTF_8)
        FileChannel.open(tmp, StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING).use { ch ->
            ch.write(ByteBuffer.wrap(bytes))
            ch.force(true)
        }
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    @Synchronized
    override fun load(id: String): Operation? {
        val f = fileFor(id)
        return if (Files.isRegularFile(f)) readFile(f) else null
    }

    @Synchronized
    override fun all(): List<Operation> {
        val bad = mutableListOf<String>()
        val ops = Files.newDirectoryStream(directory, "op-*.json").use { stream ->
            stream.mapNotNull { f ->
                readFile(f) ?: run {
                    bad += f.fileName.toString()
                    null
                }
            }
        }
        unreadableFiles = bad
        return ops.sortedBy { it.createdAt }
    }

    override fun unreadable(): List<String> = unreadableFiles

    private fun readFile(f: Path): Operation? = try {
        json.decodeFromString(OperationDto.serializer(), Files.readString(f)).toOperation()
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    } catch (e: IOException) {
        null
    }

    private fun fileFor(id: String): Path {
        require(ID_PATTERN.matches(id)) { "bad operation id" }
        return directory.resolve("op-$id.json")
    }

    companion object {
        val ID_PATTERN = Regex("^[A-Za-z0-9-]{8,64}$")
    }
}

@Serializable
private data class SnapshotDto(val absent: Boolean = false, val value: String? = null)

@Serializable
private data class ProposedDto(val delete: Boolean = false, val value: String? = null)

@Serializable
private data class EntryDto(
    val table: String,
    val name: String,
    val userId: Int,
    val previous: SnapshotDto,
    val proposed: ProposedDto,
    val phase: String,
    val outcome: String,
    val note: String = "",
    val observed: SnapshotDto? = null,
    val effectNote: String? = null,
    val restoreStatus: String = RestoreStatus.NOT_RESTORED.name,
    val restoreNote: String = "",
    val restoredAt: Long? = null,
    val startsBaseline: Boolean = false,
    val clearsBaseline: Boolean = false,
    val restoresOperationId: String? = null,
)

@Serializable
private data class OperationDto(
    val version: Int = 1,
    val id: String,
    val kind: String,
    val source: String,
    val label: String,
    val createdAt: Long,
    val backend: String,
    val deviceFingerprint: String,
    val status: String,
    val entries: List<EntryDto>,
    val profileId: String? = null,
    val restoresOperationId: String? = null,
    val finishedAt: Long? = null,
    val note: String = "",
)

private fun Snapshot.toDto() = when (this) {
    Snapshot.Absent -> SnapshotDto(absent = true)
    is Snapshot.Present -> SnapshotDto(value = value)
}

private fun SnapshotDto.toSnapshot(): Snapshot = if (absent) Snapshot.Absent else Snapshot.Present(requireNotNull(value) { "snapshot has no value" })

private fun Proposed.toDto() = when (this) {
    Proposed.Delete -> ProposedDto(delete = true)
    is Proposed.Put -> ProposedDto(value = value)
}

private fun ProposedDto.toProposed(): Proposed = if (delete) Proposed.Delete else Proposed.Put(requireNotNull(value) { "proposed has no value" })

private fun JournalEntry.toDto() = EntryDto(
    key.table.wire, key.name, key.userId, previous.toDto(), proposed.toDto(), phase.name, outcome.name, note,
    observedAfterWrite?.toDto(), effectNote, restoreStatus.name, restoreNote, restoredAt, startsBaseline, clearsBaseline, restoresOperationId,
)

private fun EntryDto.toEntry() = JournalEntry(
    key = SettingKey.of(requireNotNull(SettingsTable.fromWire(table)) { "bad table" }, name, userId),
    previous = previous.toSnapshot(),
    proposed = proposed.toProposed(),
    phase = EntryPhase.valueOf(phase),
    outcome = EntryOutcome.valueOf(outcome),
    note = note,
    observedAfterWrite = observed?.toSnapshot(),
    effectNote = effectNote,
    restoreStatus = RestoreStatus.valueOf(restoreStatus),
    restoreNote = restoreNote,
    restoredAt = restoredAt,
    startsBaseline = startsBaseline,
    clearsBaseline = clearsBaseline,
    restoresOperationId = restoresOperationId,
)

private fun Operation.toDto() = OperationDto(
    1, id, kind.name, source.name, label, createdAt, backend.name, deviceFingerprint, status.name,
    entries.map { it.toDto() }, profileId, restoresOperationId, finishedAt, note,
)

private fun OperationDto.toOperation(): Operation {
    require(version == 1) { "unsupported journal version" }
    return Operation(
        id, OperationKind.valueOf(kind), RequestSource.valueOf(source), label, createdAt, AccessKind.valueOf(backend), deviceFingerprint,
        OperationStatus.valueOf(status), entries.map { it.toEntry() }, profileId, restoresOperationId, finishedAt, note,
    )
}
