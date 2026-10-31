package ai.droidcommand.app.localmodels.download

import ai.droidcommand.llm.local.ChatModelCatalog
import ai.droidcommand.llm.local.ModelDownloadPolicy
import ai.droidcommand.llm.local.ModelMetadata
import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

sealed interface EnqueueResult {
    data object Enqueued : EnqueueResult

    /** Nothing was scheduled. [reason] is safe to show. */
    data class Refused(val reason: String) : EnqueueResult
}

/**
 * Starts, cancels and observes background model downloads. Unique work per model id, so a second request while one is
 * running or queued is ignored rather than started twice.
 *
 * Downloads wait for an unmetered network unless the caller passes `allowMetered = true` (a deliberate user choice:
 * models are hundreds of megabytes to gigabytes) and for storage that is not low. Never run on a device (see
 * [ModelDownloadWorker]).
 */
@Singleton
class ModelDownloadScheduler @Inject constructor(@ApplicationContext private val context: Context) {
    private val workManager get() = WorkManager.getInstance(context)

    fun enqueue(modelId: String, allowMetered: Boolean = false): EnqueueResult {
        val model = ChatModelCatalog.find(modelId) ?: return EnqueueResult.Refused("This model is not in the download catalog.")
        return enqueue(model, allowMetered)
    }

    fun enqueue(model: ModelMetadata, allowMetered: Boolean = false): EnqueueResult {
        ModelDownloadPolicy.problem(model)?.let { return EnqueueResult.Refused("This model cannot be downloaded in the app: $it.") }
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(
                workDataOf(
                    ModelDownloadWorker.KEY_MODEL_ID to model.id,
                    ModelDownloadWorker.KEY_DISPLAY_NAME to model.displayName,
                    ModelDownloadWorker.KEY_FILE_NAME to model.fileName,
                    ModelDownloadWorker.KEY_SHA256 to model.sha256,
                    ModelDownloadWorker.KEY_CONTEXT to model.contextTokens,
                    ModelDownloadWorker.KEY_SIZE to (model.sizeBytes ?: -1L),
                    ModelDownloadWorker.KEY_URL to (model.url ?: ""),
                ),
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (allowMetered) NetworkType.CONNECTED else NetworkType.UNMETERED)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        // KEEP: an in-flight or queued download of this model continues; a finished or failed one is replaced, so a retry
        // after a failure starts a new run (which resumes any retained .part file).
        workManager.enqueueUniqueWork(workName(model.id), ExistingWorkPolicy.KEEP, request)
        return EnqueueResult.Enqueued
    }

    /** Stops the download. The `.part` file stays, so enqueueing the model again continues from it. */
    fun cancel(modelId: String) {
        workManager.cancelUniqueWork(workName(modelId))
    }

    fun observe(modelId: String): Flow<ModelDownloadState> =
        workManager.getWorkInfosForUniqueWorkFlow(workName(modelId)).map { infos ->
            val info = infos.lastOrNull()
            ModelDownloadState.from(
                state = info?.state,
                bytesSoFar = info?.progress?.getLong(ModelDownloadWorker.KEY_BYTES, 0L) ?: 0L,
                error = info?.outputData?.getString(ModelDownloadWorker.KEY_ERROR),
            )
        }

    private fun workName(modelId: String) = "model-download-$modelId"

    private companion object {
        const val BACKOFF_SECONDS = 30L
    }
}
