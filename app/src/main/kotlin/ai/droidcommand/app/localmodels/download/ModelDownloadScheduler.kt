package ai.droidcommand.app.localmodels.download

import ai.droidcommand.app.localmodels.LocalModelRuntime
import ai.droidcommand.llm.local.ChatModelCatalog
import ai.droidcommand.llm.local.ModelDownloadPolicy
import ai.droidcommand.llm.local.ModelMetadata
import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

sealed interface EnqueueResult {
    data object Enqueued : EnqueueResult

    /** Nothing was scheduled. [reason] is safe to show. */
    data class Refused(val reason: String) : EnqueueResult
}

/**
 * Starts, stops, cancels and observes background model downloads. Unique work per model id, so a second request while
 * one is running or queued is ignored rather than started twice.
 *
 * Start, Stop, Cancel: [enqueue] starts a download, or **resumes** one whose partial file was kept. [stop] ends the
 * transfer and **keeps** the partial file. [cancel] ends it and **deletes** the partial file.
 *
 * Downloads wait for an unmetered network unless the caller passes `allowMetered = true` (a deliberate user choice:
 * models are hundreds of megabytes to gigabytes) and for storage that is not low. Never run on a device (see
 * [ModelDownloadWorker]).
 */
@Singleton
class ModelDownloadScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val localModels: LocalModelRuntime,
    private val stopSignals: DownloadStopSignals,
) {
    private val workManager get() = WorkManager.getInstance(context)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        // A new Start forgets an old Cancel that had not been carried out, so it can never discard this download.
        stopSignals.clear(model.id)
        // KEEP: an in-flight or queued download of this model continues; a finished or failed one is replaced, so a retry
        // after a failure starts a new run (which resumes any retained .part file).
        workManager.enqueueUniqueWork(workName(model.id), ExistingWorkPolicy.KEEP, request)
        return EnqueueResult.Enqueued
    }

    /** Stop: ends the transfer and keeps the `.part` file, so enqueueing the model again continues from it. */
    fun stop(modelId: String) {
        stopSignals.clear(modelId)
        workManager.cancelUniqueWork(workName(modelId))
    }

    /**
     * Cancel: ends the transfer and deletes the `.part` file. A running worker is asked to delete its own file once it has
     * stopped writing it; with nothing running, the file is deleted here. A Start that races this call in the same
     * instant could leave a small partial behind, which then shows as Stopped and can be cancelled again.
     */
    fun cancel(model: ModelMetadata) {
        io.launch {
            val running = runCatching {
                workManager.getWorkInfosForUniqueWorkFlow(workName(model.id)).first().any { it.state == WorkInfo.State.RUNNING }
            }.getOrDefault(false)
            if (running) stopSignals.requestDiscard(model.id)
            workManager.cancelUniqueWork(workName(model.id))
            if (!running) {
                localModels.discardPartial(model)
                stopSignals.notifyChanged(model.id)
            }
        }
    }

    fun observe(model: ModelMetadata): Flow<ModelDownloadState> =
        combine(
            workManager.getWorkInfosForUniqueWorkFlow(workName(model.id)),
            // Looks at the disk again when a partial file was deleted, or finished being written after a stop.
            stopSignals.changes.filter { it == model.id }.onStart { emit(model.id) },
        ) { infos, _ ->
            val info = infos.lastOrNull()
            val bytes = info?.progress?.getLong(ModelDownloadWorker.KEY_BYTES, 0L) ?: 0L
            ModelDownloadState.from(
                state = info?.state,
                bytesSoFar = bytes,
                error = info?.outputData?.getString(ModelDownloadWorker.KEY_ERROR),
                partialBytes = { localModels.partialBytes(model) },
            )
        }.flowOn(Dispatchers.IO)

    private fun workName(modelId: String) = "model-download-$modelId"

    private companion object {
        const val BACKOFF_SECONDS = 30L
    }
}
