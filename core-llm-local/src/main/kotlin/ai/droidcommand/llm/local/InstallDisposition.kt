package ai.droidcommand.llm.local

/**
 * What a background scheduler should do with the outcome of one [ModelRepository.install] attempt. Kept free of any
 * Android type so the decision is JVM-tested; the WorkManager worker in `:app` only translates it.
 *
 * A retryable failure kept its `.part` prefix, so a later attempt resumes rather than restarts. After [maxAttempts]
 * attempts the work is given up, but the prefix stays: starting the download again continues it.
 */
enum class InstallDisposition {
    DONE,
    RETRY,
    FAIL,
    ;

    companion object {
        const val DEFAULT_MAX_ATTEMPTS = 10

        /** [attempt] is zero for the first try, matching WorkManager's `runAttemptCount`. */
        fun of(result: ModelInstallResult, attempt: Int, maxAttempts: Int = DEFAULT_MAX_ATTEMPTS): InstallDisposition = when (result) {
            is ModelInstallResult.Installed -> DONE
            is ModelInstallResult.Failed -> if (result.retryable && attempt + 1 < maxAttempts) RETRY else FAIL
        }
    }
}
