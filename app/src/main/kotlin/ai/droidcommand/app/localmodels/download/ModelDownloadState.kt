package ai.droidcommand.app.localmodels.download

import androidx.work.WorkInfo

/** What the UI shows for one model's background download. Derived from WorkManager, which survives process death. */
sealed interface ModelDownloadState {
    /** No download has been requested, or the last one was cancelled and its partial file discarded. */
    data object Idle : ModelDownloadState

    /** The download was stopped (or interrupted for good) and [bytesSoFar] is kept on disk; Start continues after it. */
    data class Stopped(val bytesSoFar: Long) : ModelDownloadState

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
         * [error] is the failure reason the worker put in its output data. [partialBytes] is only asked for when nothing
         * is running (it reads the disk): a kept partial file turns "nothing requested" or "cancelled" into [Stopped],
         * which is also how a partial left by an earlier session shows up.
         */
        fun from(
            state: WorkInfo.State?,
            bytesSoFar: Long,
            error: String?,
            partialBytes: () -> Long = { 0L },
        ): ModelDownloadState = when (state) {
            null, WorkInfo.State.CANCELLED -> partialBytes().let { kept -> if (kept > 0L) Stopped(maxOf(kept, bytesSoFar)) else Idle }
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> Queued(bytesSoFar)
            WorkInfo.State.RUNNING -> Running(bytesSoFar)
            WorkInfo.State.SUCCEEDED -> Installed
            WorkInfo.State.FAILED -> Failed(error ?: "The download failed.")
        }
    }
}
