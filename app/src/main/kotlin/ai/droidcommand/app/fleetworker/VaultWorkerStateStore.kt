package ai.droidcommand.app.fleetworker

import ai.droidcommand.config.SecretsVault
import ai.droidcommand.fleet.worker.FleetProtocol
import ai.droidcommand.fleet.worker.WorkerPersisted
import ai.droidcommand.fleet.worker.WorkerStateStore

/**
 * What the worker remembers (its credential, pairing, and results not yet acknowledged), kept as one record in the app's
 * Keystore-backed secrets vault so the coordinator credential is never in plain storage. Reads come from a cache that every
 * save updates; a record that cannot be read (for instance the Keystore key was lost) is treated as "not paired", which is
 * safe: the owner pairs again. A save that fails is thrown, not swallowed, because dropping a result report silently would
 * leave the coordinator guessing.
 */
class VaultWorkerStateStore(private val vault: SecretsVault, private val secretId: String = SECRET_ID) : WorkerStateStore {
    private var cache: WorkerPersisted? = null

    @Synchronized
    override fun load(): WorkerPersisted = cache ?: read().also { cache = it }

    @Synchronized
    override fun save(state: WorkerPersisted) {
        vault.putSecret(secretId, FleetProtocol.json.encodeToString(WorkerPersisted.serializer(), state))
        cache = state
    }

    private fun read(): WorkerPersisted =
        runCatching { vault.getSecret(secretId)?.let { FleetProtocol.json.decodeFromString(WorkerPersisted.serializer(), it) } }
            .getOrNull() ?: WorkerPersisted()

    companion object {
        const val SECRET_ID = "fleet.worker.state.v1"
    }
}
