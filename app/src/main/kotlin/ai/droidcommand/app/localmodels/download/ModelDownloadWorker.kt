// Background-download design adapted from OpenDroid (https://github.com/Victorious93/opendroid),
// app/src/main/java/com/opendroid/ai/core/llm/ModelDownloadWorker.kt
// Copyright (c) 2026 OpenDroid Contributors, licensed under the Apache License, Version 2.0
// (http://www.apache.org/licenses/LICENSE-2.0). Reimplemented, not copied: what carries over is the CoroutineWorker with
// a data-sync foreground notification, resolving the model from the app's own catalog instead of trusting WorkManager
// input data, resuming a retained partial file, and treating a stop as "retry later" rather than a failure. The
// transfer itself and every integrity decision reuse this repository's core-llm-local (HttpsModelDownloader and
// ModelRepository.install), not OpenDroid's OkHttp/Room/LiteRT code.
package ai.droidcommand.app.localmodels.download

import ai.droidcommand.app.localmodels.LocalModelRuntime
import ai.droidcommand.llm.local.ChatModelCatalog
import ai.droidcommand.llm.local.HttpsModelDownloader
import ai.droidcommand.llm.local.InstallDisposition
import ai.droidcommand.llm.local.ModelInstallResult
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Downloads one catalog model (`ChatModelCatalog`) in the background, resumably, and installs it only after
 * `ModelRepository.install` has verified its size, GGUF header and SHA-256.
 *
 * Retry: a network/disk interruption keeps the `.part` file and returns [Result.retry] (WorkManager's exponential
 * backoff), up to [InstallDisposition.DEFAULT_MAX_ATTEMPTS] attempts; the next attempt resumes with an HTTP Range
 * request. A failed integrity check deletes the file and returns [Result.failure]. A stop (constraint lost, system
 * pressure, user cancel) is never reported as a failure.
 *
 * COMPILES; NEVER RUN on a device or emulator. The scheduling, foreground-service start, notification and resume
 * behaviour here are unverified; the integrity decisions it relies on are JVM-tested in core-llm-local.
 */
class ModelDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun localModels(): LocalModelRuntime
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(modelLabel(), bytes = null, total = null)

    override suspend fun doWork(): Result {
        val modelId = inputData.getString(KEY_MODEL_ID) ?: return failure("No model was requested.")
        val model = ChatModelCatalog.find(modelId) ?: return failure("This model is not in the download catalog.")
        val runtime = EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java).localModels()

        try {
            setForeground(foregroundInfo(model.displayName, bytes = null, total = model.sizeBytes))
        } catch (e: IllegalStateException) {
            // Android 12+ refuses to start a foreground service from the background in some states. The download still
            // runs as ordinary work; it may be stopped sooner, and the partial file lets the next run continue.
            Log.w(TAG, "Could not start the download as a foreground service; continuing as regular work", e)
        }

        var lastReportMillis = 0L
        val result = withContext(Dispatchers.IO) {
            runtime.installDownloadable(
                metadata = model,
                downloader = HttpsModelDownloader(),
                onProgress = { bytes ->
                    val now = System.currentTimeMillis()
                    if (now - lastReportMillis >= PROGRESS_INTERVAL_MILLIS) {
                        lastReportMillis = now
                        setProgressAsync(workDataOf(KEY_BYTES to bytes))
                        setForegroundAsync(foregroundInfo(model.displayName, bytes, model.sizeBytes))
                    }
                },
                cancelled = { isStopped },
            )
        }

        if (isStopped) return Result.retry()
        return when (InstallDisposition.of(result, runAttemptCount)) {
            InstallDisposition.DONE -> Result.success()
            InstallDisposition.RETRY -> Result.retry()
            InstallDisposition.FAIL -> failure(
                (result as? ModelInstallResult.Failed)?.reason ?: "The download failed.",
            )
        }
    }

    private fun failure(reason: String): Result = Result.failure(workDataOf(KEY_ERROR to reason))

    private fun modelLabel(): String =
        inputData.getString(KEY_MODEL_ID)?.let { ChatModelCatalog.find(it)?.displayName } ?: "model"

    private fun foregroundInfo(label: String, bytes: Long?, total: Long?): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Model downloads", NotificationManager.IMPORTANCE_LOW),
        )
        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading $label")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (bytes != null && total != null && total > 0) {
            builder.setProgress(PROGRESS_MAX, (bytes * PROGRESS_MAX / total).toInt().coerceIn(0, PROGRESS_MAX), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        val notification = builder.build()
        val notificationId = NOTIFICATION_ID_BASE + (id.hashCode() and 0xFFFF)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    companion object {
        const val KEY_MODEL_ID = "model_id"
        const val KEY_BYTES = "bytes"
        const val KEY_ERROR = "error"
        private const val TAG = "ModelDownloadWorker"
        private const val CHANNEL_ID = "model_downloads"
        private const val NOTIFICATION_ID_BASE = 4200
        private const val PROGRESS_MAX = 100
        private const val PROGRESS_INTERVAL_MILLIS = 1_000L
    }
}
