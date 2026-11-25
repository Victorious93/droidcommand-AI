package ai.droidcommand.app.communications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Victorious Framework: the periodic "check for new messages and draft replies" job. Drafts only: it reuses the same runtime
 * as the Check now button, so it can never send, and the emergency stop and the per-conversation switches apply unchanged.
 *
 * It always reports success: an unreachable VictorSMS or a model failure is not something WorkManager's retry backoff can
 * fix, and the next period tries again. COMPILES; NEVER RUN on a device. Timing, Doze behaviour and battery cost are unmeasured.
 */
class DraftCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun draftRuntime(): DraftRuntime
    }

    override suspend fun doWork(): Result {
        val runtime = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java).draftRuntime()
        withContext(Dispatchers.IO) { runtime.checkAndWait(WAIT_MS) }
        return Result.success()
    }

    companion object {
        /** Well inside WorkManager's execution window; unfinished drafts are picked up by the next period. */
        const val WAIT_MS = 4 * 60_000L
    }
}
