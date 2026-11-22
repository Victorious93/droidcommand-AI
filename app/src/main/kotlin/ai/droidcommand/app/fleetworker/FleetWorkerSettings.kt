package ai.droidcommand.app.fleetworker

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The owner's two switches for taking fleet jobs on this phone. **Both are off until the owner turns them on**, they are
 * independent, and each is read again at the moment it matters (every job, every offer), so turning one off takes effect
 * immediately rather than at the next restart.
 *
 * - [workerEnabled] "Fleet worker mode": whether this phone talks to the fleet coordinator and takes its jobs at all.
 * - [batchApprovalEnabled] "Batch approval": whether the owner may approve a reviewed batch of jobs once instead of
 *   confirming each. It does nothing unless the worker mode is also on and a job type asks for confirmation.
 */
interface FleetWorkerSettings {
    val workerEnabled: StateFlow<Boolean>
    val batchApprovalEnabled: StateFlow<Boolean>

    fun setWorkerEnabled(on: Boolean)

    fun setBatchApprovalEnabled(on: Boolean)
}

/** Two booleans in app-private SharedPreferences. A missing key means off. */
@Singleton
class SharedPreferencesFleetWorkerSettings @Inject constructor(@ApplicationContext context: Context) : FleetWorkerSettings {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val worker = MutableStateFlow(prefs.getBoolean(KEY_WORKER, false))
    private val batch = MutableStateFlow(prefs.getBoolean(KEY_BATCH, false))

    override val workerEnabled: StateFlow<Boolean> = worker.asStateFlow()
    override val batchApprovalEnabled: StateFlow<Boolean> = batch.asStateFlow()

    override fun setWorkerEnabled(on: Boolean) {
        prefs.edit(commit = true) { putBoolean(KEY_WORKER, on) }
        worker.value = on
    }

    override fun setBatchApprovalEnabled(on: Boolean) {
        prefs.edit(commit = true) { putBoolean(KEY_BATCH, on) }
        batch.value = on
    }

    companion object {
        const val FILE = "fleet_worker_settings"
        const val KEY_WORKER = "worker.enabled"
        const val KEY_BATCH = "batch_approval.enabled"
    }
}

/** For tests and previews: same contract, nothing persisted. Both start off. */
class InMemoryFleetWorkerSettings(worker: Boolean = false, batch: Boolean = false) : FleetWorkerSettings {
    private val w = MutableStateFlow(worker)
    private val b = MutableStateFlow(batch)
    override val workerEnabled: StateFlow<Boolean> = w.asStateFlow()
    override val batchApprovalEnabled: StateFlow<Boolean> = b.asStateFlow()

    override fun setWorkerEnabled(on: Boolean) {
        w.value = on
    }

    override fun setBatchApprovalEnabled(on: Boolean) {
        b.value = on
    }
}
