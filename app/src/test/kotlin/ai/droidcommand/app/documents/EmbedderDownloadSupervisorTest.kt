package ai.droidcommand.app.documents

import ai.droidcommand.app.ui.chat.NetworkLink
import ai.droidcommand.llm.local.ModelInstallResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EmbedderDownloadSupervisorTest {
    private val installed = ModelInstallResult.Installed("/m")
    private val flaky = ModelInstallResult.Failed("connection reset", retryable = true)

    private class Clock {
        var now = 0L
        val sleeps = mutableListOf<Long>()

        fun sleep(ms: Long) {
            sleeps += ms
            now += ms
        }
    }

    private fun supervisor(
        clock: Clock,
        link: () -> NetworkLink = { NetworkLink.UNMETERED },
        install: (onProgress: (Long) -> Unit, cancelled: () -> Boolean) -> ModelInstallResult,
    ) = EmbedderDownloadSupervisor(install, link, clock::sleep, { clock.now })

    private fun EmbedderDownloadSupervisor.go(
        allowMetered: Boolean = false,
        userCancelled: () -> Boolean = { false },
        retrying: MutableList<String> = mutableListOf(),
    ) = run(allowMetered, userCancelled, {}, { retrying += it })

    @Test
    fun retries_a_retryable_failure_then_succeeds() {
        val results = ArrayDeque(listOf(flaky, flaky, installed))
        val retrying = mutableListOf<String>()
        val c = Clock()
        val out = supervisor(c) { _, _ -> results.removeFirst() }.go(retrying = retrying)
        assertEquals(DownloadOutcome.Finished(installed), out)
        assertEquals(2, retrying.size)
        assertEquals(2_000L + 5_000L, c.sleeps.sum())
    }

    @Test
    fun gives_up_after_max_failures_without_progress() {
        var calls = 0
        val out = supervisor(Clock()) { _, _ ->
            calls++
            flaky
        }.go()
        assertEquals(DownloadOutcome.Finished(flaky), out)
        assertEquals(4, calls)
    }

    @Test
    fun progress_resets_the_failure_count() {
        var calls = 0
        val out = supervisor(Clock()) { progress, _ ->
            calls++
            progress(calls.toLong())
            if (calls < 9) flaky else installed
        }.go()
        assertEquals(DownloadOutcome.Finished(installed), out)
        assertEquals(9, calls)
    }

    @Test
    fun non_retryable_failure_is_not_retried() {
        var calls = 0
        val bad = ModelInstallResult.Failed("SHA-256 mismatch")
        val out = supervisor(Clock()) { _, _ ->
            calls++
            bad
        }.go()
        assertEquals(DownloadOutcome.Finished(bad), out)
        assertEquals(1, calls)
    }

    @Test
    fun user_cancel_stops_without_another_attempt() {
        var calls = 0
        var cancel = false
        val out = supervisor(Clock()) { _, _ ->
            calls++
            cancel = true
            flaky
        }.go(userCancelled = { cancel })
        assertEquals(DownloadOutcome.Cancelled, out)
        assertEquals(1, calls)
    }

    @Test
    fun turning_metered_mid_download_interrupts_and_pauses() {
        val c = Clock()
        var link = NetworkLink.UNMETERED
        val out = supervisor(c, { link }) { _, cancelled ->
            assertTrue(!cancelled())
            link = NetworkLink.METERED
            c.now += 3_000
            assertTrue(cancelled(), "the download must be told to stop")
            ModelInstallResult.Failed("Cancelled")
        }.go()
        assertEquals(DownloadOutcome.PausedForMetered, out)
    }

    @Test
    fun metered_mid_download_is_ignored_once_the_user_agreed() {
        val c = Clock()
        val out = supervisor(c, { NetworkLink.METERED }) { _, cancelled ->
            c.now += 10_000
            assertTrue(!cancelled())
            installed
        }.go(allowMetered = true)
        assertEquals(DownloadOutcome.Finished(installed), out)
    }

    @Test
    fun link_is_probed_at_most_once_per_interval() {
        var probes = 0
        supervisor(Clock(), { probes++.let { NetworkLink.UNMETERED } }) { _, cancelled ->
            repeat(100) { cancelled() }
            installed
        }.go()
        assertEquals(1, probes, "only the pre-attempt probe; no time passed so no mid-download probe")
    }

    @Test
    fun a_retry_that_finds_a_metered_link_pauses_instead_of_downloading() {
        var link = NetworkLink.UNMETERED
        var calls = 0
        val out = supervisor(Clock(), { link }) { _, _ ->
            calls++
            link = NetworkLink.METERED
            flaky
        }.go()
        assertEquals(DownloadOutcome.PausedForMetered, out)
        assertEquals(1, calls)
    }

    @Test
    fun a_retry_with_no_network_waits_and_does_not_call_install() {
        var link = NetworkLink.UNMETERED
        var calls = 0
        val out = supervisor(Clock(), { link }) { _, _ ->
            calls++
            link = NetworkLink.NONE
            flaky
        }.go()
        assertIs<DownloadOutcome.Finished>(out)
        assertEquals(1, calls, "install is not called while there is no network")
    }
}
