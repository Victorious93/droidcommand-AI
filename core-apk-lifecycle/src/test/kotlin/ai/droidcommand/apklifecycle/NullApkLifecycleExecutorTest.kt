package ai.droidcommand.apklifecycle

import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NullApkLifecycleExecutorTest {
    private val executor = NullApkLifecycleExecutor()

    @Test
    fun `install fails explicitly rather than fabricating success`() {
        val result = assertIs<InstallResult.Failure>(executor.install(InstallRequest("/tmp/app.apk", "com.example.app")))
        assertTrue(result.reason.contains("no real device"))
    }

    @Test
    fun `uninstall, launch, collectLogs, and runInstrumentedTests all fail explicitly`() {
        assertIs<UninstallResult.Failure>(executor.uninstall("com.example.app"))
        assertIs<LaunchResult.Failure>(executor.launch("com.example.app"))
        assertIs<LogsResult.Failure>(executor.collectLogs("com.example.app"))
        assertIs<TestRunResult.Failure>(executor.runInstrumentedTests("com.example.app"))
    }
}
