package ai.droidcommand.app.localmodels.download

import androidx.work.WorkInfo

/** What the UI shows for one model's background download. Derived from WorkManager, which survives process death. */
sealed interface ModelDownloadState {
    /** No download has been requested, or the last one was cancelled. */
    data object Idle : ModelDownloadState

    /** Waiting for a network (or storage) constraint, or for a retry's backoff. */
    data class Queued(val bytesSoFar: Long) : ModelDownloadState

    data class Running(val bytesSoFar: Long) : ModelDownloadState

    /** Downloaded and verified (size, GGUF header, SHA-256); the model is registered and loadable. */
    data object Installed : ModelDownloadState

    /** Not retried: a failed integrity check, an unusable catalog entry, or the retry limit. [reason] is safe to show. */
    data class Failed(val reason: String) : ModelDownloadState

    companion object {
        /**
         * Pure so it is JVM-tested without a WorkManager. [bytesSoFar] is the worker's last reported progress (0 if none);
         * [error] is the failure reason the worker put in its output data.
         */
        fun from(state: WorkInfo.State?, bytesSoFar: Long, error: String?): ModelDownloadState = when (state) {
            null, WorkInfo.State.CANCELLED -> Idle
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> Queued(bytesSoFar)
            WorkInfo.State.RUNNING -> Running(bytesSoFar)
            WorkInfo.State.SUCCEEDED -> Installed
            WorkInfo.State.FAILED -> Failed(error ?: "The download failed.")
        }
    }
}
