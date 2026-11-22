package ai.droidcommand.fleet.worker

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import java.io.File

/** What the worker must remember across process death and reboot, so it can reconcile with the coordinator. */
@Serializable
data class RunningJob(val jobId: String, val attempt: Int, val op: String, val startedMs: Long)

@Serializable
data class WorkerPersisted(
    val deviceId: String? = null,
    // In :app the credential is stored in EncryptedSharedPreferences/Keystore, never in plain JSON.
    val credential: String? = null,
    val coordinatorUrl: String? = null,
    val coordinatorSpkiSha256: String? = null,
    val revoked: Boolean = false,
    // Bounded de-duplication window of coordinator msg_ids.
    val seenInbound: List<String> = emptyList(),
    val running: List<RunningJob> = emptyList(),
    // Serialized envelopes not yet acknowledged by the coordinator.
    val pendingOutbound: List<String> = emptyList(),
    val paused: Boolean = false,
)

interface WorkerStateStore {
    fun load(): WorkerPersisted
    fun save(state: WorkerPersisted)

    /**
     * Store [transform] of the state as it is right now, as one update. Callers must use this instead of load-then-save.
     * Job results, pause, and revocation are written from different threads; a save of a snapshot taken a moment earlier
     * drops a result that was queued in between, or writes a credential back after the phone was revoked.
     */
    fun update(transform: (WorkerPersisted) -> WorkerPersisted): WorkerPersisted
}

class InMemoryWorkerStateStore(private var s: WorkerPersisted = WorkerPersisted()) : WorkerStateStore {
    @Synchronized override fun load() = s

    @Synchronized override fun save(state: WorkerPersisted) {
        s = state
    }

    @Synchronized override fun update(transform: (WorkerPersisted) -> WorkerPersisted): WorkerPersisted {
        val next = transform(s)
        s = next
        return next
    }
}

/** Atomic JSON file store (write temp, fsync, rename). For the credential use a Keystore-backed store in :app. */
class JsonFileWorkerStateStore(private val file: File) : WorkerStateStore {
    @Synchronized override fun load(): WorkerPersisted =
        runCatching { FleetProtocol.json.decodeFromString(WorkerPersisted.serializer(), file.readText()) }.getOrDefault(WorkerPersisted())

    @Synchronized override fun save(state: WorkerPersisted) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.outputStream().use {
            it.write(FleetProtocol.json.encodeToString(WorkerPersisted.serializer(), state).toByteArray())
            it.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "could not replace worker state file" }
        }
    }

    @Synchronized override fun update(transform: (WorkerPersisted) -> WorkerPersisted): WorkerPersisted {
        val next = transform(load())
        save(next)
        return next
    }
}
