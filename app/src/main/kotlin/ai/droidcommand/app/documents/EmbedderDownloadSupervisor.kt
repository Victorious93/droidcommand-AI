package ai.droidcommand.app.documents

import ai.droidcommand.app.ui.chat.NetworkLink
import ai.droidcommand.llm.local.ModelInstallResult

sealed class DownloadOutcome {
    data class Finished(val result: ModelInstallResult) : DownloadOutcome()

    /** The connection became metered (or was metered on a retry) and the user has not agreed to metered data. */
    data object PausedForMetered : DownloadOutcome()

    data object Cancelled : DownloadOutcome()
}

/**
 * Drives an embedding-model install: retries a retryable failure (see [ModelInstallResult.Failed.retryable]) with
 * backoff, and stops the download when the connection turns metered mid-transfer unless the user already agreed
 * to metered data. Each retry resumes the `.part` file, so nothing is re-fetched. Pure JVM: the install, the network
 * probe, the clock and the sleep are injected.
 *
 * Retries are counted per run of consecutive failures; an attempt that made progress resets the count, so a flaky link
 * that keeps advancing is not abandoned, while a dead one gives up after [maxFailures] attempts.
 */
class EmbedderDownloadSupervisor(
    private val install: (onProgress: (Long) -> Unit, cancelled: () -> Boolean) -> ModelInstallResult,
    private val link: () -> NetworkLink,
    private val sleep: (Long) -> Unit = Thread::sleep,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val maxFailures: Int = 4,
    private val backoffMillis: List<Long> = listOf(2_000L, 5_000L, 15_000L),
    private val linkCheckIntervalMillis: Long = 2_000L,
) {
    fun run(
        allowMetered: Boolean,
        userCancelled: () -> Boolean,
        onProgress: (Long) -> Unit,
        onRetrying: (message: String) -> Unit,
    ): DownloadOutcome {
        var failures = 0
        while (true) {
            if (userCancelled()) return DownloadOutcome.Cancelled
            val now = link()
            if (!allowMetered && now == NetworkLink.METERED) return DownloadOutcome.PausedForMetered

            var progressed = false
            var pausedMetered = false
            var lastCheck = nowMillis()
            val cancelled = {
                when {
                    userCancelled() || pausedMetered -> true
                    allowMetered || nowMillis() - lastCheck < linkCheckIntervalMillis -> false
                    else -> {
                        lastCheck = nowMillis()
                        pausedMetered = link() == NetworkLink.METERED
                        pausedMetered
                    }
                }
            }
            val result = if (now == NetworkLink.NONE) {
                ModelInstallResult.Failed("No network connection", retryable = true)
            } else {
                install({ bytes -> progressed = true; onProgress(bytes) }, cancelled)
            }

            if (result is ModelInstallResult.Installed) return DownloadOutcome.Finished(result)
            if (userCancelled()) return DownloadOutcome.Cancelled
            if (pausedMetered) return DownloadOutcome.PausedForMetered
            if (result !is ModelInstallResult.Failed || !result.retryable) return DownloadOutcome.Finished(result)

            if (progressed) failures = 0
            failures++
            if (failures >= maxFailures) return DownloadOutcome.Finished(result)
            val wait = backoffMillis[minOf(failures - 1, backoffMillis.lastIndex)]
            onRetrying("${result.reason}. Retrying in ${wait / 1000}s (attempt ${failures + 1} of $maxFailures)…")
            var waited = 0L
            while (waited < wait && !userCancelled()) {
                val slice = minOf(WAIT_SLICE_MILLIS, wait - waited)
                sleep(slice)
                waited += slice
            }
        }
    }

    private companion object {
        const val WAIT_SLICE_MILLIS = 500L
    }
}
