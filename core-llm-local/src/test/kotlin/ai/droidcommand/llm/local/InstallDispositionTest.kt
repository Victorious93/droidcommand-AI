package ai.droidcommand.llm.local

import kotlin.test.Test
import kotlin.test.assertEquals

class InstallDispositionTest {
    private val installed = ModelInstallResult.Installed("/m.gguf")
    private val flaky = ModelInstallResult.Failed("timeout", retryable = true)
    private val bad = ModelInstallResult.Failed("SHA-256 mismatch; file discarded")

    @Test
    fun `success is done and a hard failure is never retried`() {
        assertEquals(InstallDisposition.DONE, InstallDisposition.of(installed, attempt = 0))
        assertEquals(InstallDisposition.FAIL, InstallDisposition.of(bad, attempt = 0))
    }

    @Test
    fun `a retryable failure is retried until the attempts run out`() {
        assertEquals(InstallDisposition.RETRY, InstallDisposition.of(flaky, attempt = 0, maxAttempts = 3))
        assertEquals(InstallDisposition.RETRY, InstallDisposition.of(flaky, attempt = 1, maxAttempts = 3))
        assertEquals(InstallDisposition.FAIL, InstallDisposition.of(flaky, attempt = 2, maxAttempts = 3))
    }
}
