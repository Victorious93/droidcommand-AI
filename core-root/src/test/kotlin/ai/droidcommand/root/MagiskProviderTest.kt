package ai.droidcommand.root

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Proves [MagiskProvider]'s detection/execution logic against real,
 * controlled fixtures (temp-directory marker files, real `su`/`magisk`
 * shell scripts spawned as real subprocesses) rather than mocks — matching
 * `core-shell.ProcessBuilderShellExecutorTest`'s "prove the boundary is
 * real" convention. This environment has no actual Magisk install, so
 * every "Magisk present" case here is a real, honest fixture standing in
 * for one — real on-device Magisk/root verification remains
 * IMPLEMENTED — NOT RUNTIME VERIFIED (see docs/AUDIT_2026-09-05.md).
 */
class MagiskProviderTest {
    private lateinit var tempDir: File

    @BeforeTest
    fun setUp() {
        tempDir = createTempDirectory("magisk-provider-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun writeScript(name: String, content: String): String {
        val file = File(tempDir, name)
        file.writeText(content)
        file.setExecutable(true)
        return file.absolutePath
    }

    private val magiskScriptWithVersion = """
        #!/bin/sh
        if [ "${'$'}1" = "-v" ]; then
          echo "27.0:MAGISK:26000"
          exit 0
        fi
        exit 1
    """.trimIndent()

    private fun suScriptAvailable(counterFile: File? = null): String = """
        #!/bin/sh
        ${counterFile?.let { "echo call >> '${it.absolutePath}'" } ?: ""}
        if [ "${'$'}1" = "-c" ]; then
          shift
          if [ "${'$'}1" = "id -u" ]; then
            echo "0"
            exit 0
          fi
          sh -c "${'$'}1"
          exit ${'$'}?
        fi
        exit 1
        """.trimIndent()

    private val suScriptDenied = """
        #!/bin/sh
        if [ "${'$'}1" = "-c" ] && [ "${'$'}2" = "id -u" ]; then
          echo "1000"
          exit 0
        fi
        exit 13
    """.trimIndent()

    // --- isMagiskInstalled ---

    @Test
    fun `not installed when no marker path exists and the executable cannot start`() {
        val provider = MagiskProvider(
            magiskMarkerPaths = listOf(File(tempDir, "no-such-marker").absolutePath),
            magiskExecutable = File(tempDir, "no-such-magisk-binary").absolutePath,
        )
        assertFalse(provider.isMagiskInstalled())
    }

    @Test
    fun `installed via a real marker file present on disk`() {
        File(tempDir, ".magisk-marker").writeText("")
        val provider = MagiskProvider(
            magiskMarkerPaths = listOf(File(tempDir, ".magisk-marker").absolutePath),
            magiskExecutable = File(tempDir, "no-such-magisk-binary").absolutePath,
        )
        assertTrue(provider.isMagiskInstalled())
    }

    @Test
    fun `installed via a real, executable magisk binary that starts`() {
        val magiskPath = writeScript("magisk", magiskScriptWithVersion)
        val provider = MagiskProvider(
            magiskMarkerPaths = listOf(File(tempDir, "no-such-marker").absolutePath),
            magiskExecutable = magiskPath,
        )
        assertTrue(provider.isMagiskInstalled())
    }

    // --- getMagiskVersion ---

    @Test
    fun `version is null when magisk is not installed`() {
        val provider = MagiskProvider(
            magiskMarkerPaths = emptyList(),
            magiskExecutable = File(tempDir, "no-such-magisk-binary").absolutePath,
        )
        assertNull(provider.getMagiskVersion())
    }

    @Test
    fun `version is parsed from the real magisk binary's output`() {
        val magiskPath = writeScript("magisk", magiskScriptWithVersion)
        val provider = MagiskProvider(magiskMarkerPaths = emptyList(), magiskExecutable = magiskPath)
        assertEquals("27.0:MAGISK:26000", provider.getMagiskVersion())
    }

    // --- root shell probing / isRootAvailable / isAuthorized / getPrivilegeLevel ---

    @Test
    fun `root unavailable when su cannot be started`() {
        val provider = MagiskProvider(suExecutable = File(tempDir, "no-such-su").absolutePath)
        assertFalse(provider.isRootAvailable())
        assertFalse(provider.isAuthorized())
        assertEquals(PrivilegeLevel.NONE, provider.getPrivilegeLevel())
    }

    @Test
    fun `root available when a real su script reports uid 0`() {
        val suPath = writeScript("su", suScriptAvailable())
        val provider = MagiskProvider(suExecutable = suPath)
        assertTrue(provider.isRootAvailable())
        assertTrue(provider.isAuthorized())
        assertEquals(PrivilegeLevel.ROOT, provider.getPrivilegeLevel())
    }

    @Test
    fun `root shell present but not granted (non-zero uid) is not treated as available`() {
        val suPath = writeScript("su", suScriptDenied)
        val magiskPath = writeScript("magisk", magiskScriptWithVersion)
        val provider = MagiskProvider(suExecutable = suPath, magiskExecutable = magiskPath, magiskMarkerPaths = emptyList())
        assertFalse(provider.isRootAvailable())
        assertFalse(provider.isAuthorized())
        assertEquals(PrivilegeLevel.USER, provider.getPrivilegeLevel())
    }

    @Test
    fun `probe result is cached within the TTL, not re-run on every call`() {
        val counterFile = File(tempDir, "calls.log")
        val suPath = writeScript("su", suScriptAvailable(counterFile))
        val provider = MagiskProvider(suExecutable = suPath, cacheTtlMillis = 60_000)

        provider.isRootAvailable()
        provider.isRootAvailable()
        provider.getPrivilegeLevel()

        assertEquals(1, counterFile.readLines().size)

        provider.invalidateCache()
        provider.isRootAvailable()
        assertEquals(2, counterFile.readLines().size)
    }

    @Test
    fun `probe is re-run once the TTL expires, without an explicit invalidate`() {
        val counterFile = File(tempDir, "calls.log")
        val suPath = writeScript("su", suScriptAvailable(counterFile))
        val provider = MagiskProvider(suExecutable = suPath, cacheTtlMillis = 50)

        provider.isRootAvailable()
        assertEquals(1, counterFile.readLines().size)

        Thread.sleep(150)
        provider.isRootAvailable()
        assertEquals(2, counterFile.readLines().size)
    }

    // --- checkHealth ---

    @Test
    fun `health is UNAVAILABLE with a reason when magisk is not installed, regardless of probeShell`() {
        val provider = MagiskProvider(
            magiskMarkerPaths = emptyList(),
            magiskExecutable = File(tempDir, "no-such-magisk").absolutePath,
            suExecutable = File(tempDir, "no-such-su").absolutePath,
        )
        for (probeShell in listOf(false, true)) {
            val health = provider.checkHealth(probeShell = probeShell)
            assertEquals(RootProviderState.UNAVAILABLE, health.state)
            assertFalse(health.rootAvailable)
            assertTrue(health.lastError!!.contains("not detected"))
        }
    }

    @Test
    fun `health with probeShell=false never spawns su, even when magisk is installed`() {
        val counterFile = File(tempDir, "calls.log")
        val suPath = writeScript("su", suScriptAvailable(counterFile))
        val magiskPath = writeScript("magisk", magiskScriptWithVersion)
        val provider = MagiskProvider(suExecutable = suPath, magiskExecutable = magiskPath, magiskMarkerPaths = emptyList())

        val health = provider.checkHealth(probeShell = false)

        assertEquals(RootProviderState.REQUIRES_PERMISSION, health.state)
        assertFalse(health.rootAvailable)
        assertTrue(health.lastError!!.contains("not probed"))
        assertFalse(counterFile.exists(), "checkHealth(probeShell = false) must never spawn su")
    }

    @Test
    fun `health with probeShell=true reports AVAILABLE when root is genuinely functional`() {
        val suPath = writeScript("su", suScriptAvailable())
        val magiskPath = writeScript("magisk", magiskScriptWithVersion)
        val provider = MagiskProvider(suExecutable = suPath, magiskExecutable = magiskPath, magiskMarkerPaths = emptyList())

        val health = provider.checkHealth(probeShell = true)

        assertEquals(RootProviderState.AVAILABLE, health.state)
        assertTrue(health.rootAvailable)
        assertTrue(health.rootAuthorized)
        assertTrue(health.rootShellAvailable)
        assertEquals(PrivilegeLevel.ROOT, health.privilegeLevel)
        assertNull(health.lastError)
    }

    @Test
    fun `health with probeShell=true reports REQUIRES_PERMISSION when denied`() {
        val suPath = writeScript("su", suScriptDenied)
        val magiskPath = writeScript("magisk", magiskScriptWithVersion)
        val provider = MagiskProvider(suExecutable = suPath, magiskExecutable = magiskPath, magiskMarkerPaths = emptyList())

        val health = provider.checkHealth(probeShell = true)

        assertEquals(RootProviderState.REQUIRES_PERMISSION, health.state)
        assertFalse(health.rootAvailable)
        assertFalse(health.rootAuthorized)
        assertTrue(health.lastError!!.isNotBlank())
    }

    // --- getCapabilities ---

    @Test
    fun `no capabilities reported when magisk is not installed`() {
        val provider = MagiskProvider(
            magiskMarkerPaths = emptyList(),
            magiskExecutable = File(tempDir, "no-such-magisk").absolutePath,
        )
        assertTrue(provider.getCapabilities().isEmpty())
    }

    @Test
    fun `capabilities include version query only when a real version is resolvable`() {
        val magiskPath = writeScript("magisk", magiskScriptWithVersion)
        val provider = MagiskProvider(magiskMarkerPaths = emptyList(), magiskExecutable = magiskPath)

        val caps = provider.getCapabilities()

        assertTrue("magisk_detection" in caps)
        assertTrue("root_shell_execute" in caps)
        assertTrue("magisk_version_query" in caps)
        assertFalse("magisk_module_list" in caps, "module management must never be advertised — not implemented")
    }

    // --- execute (RootExecutor contract) ---

    @Test
    fun `execute fails explicitly when su cannot be started`() {
        val provider = MagiskProvider(suExecutable = File(tempDir, "no-such-su").absolutePath)
        val result = provider.execute(RootCommand(executable = "id"))
        assertIs<RootExecutionResult.Failure>(result)
        assertTrue(result.reason.contains("Failed to start"))
    }

    @Test
    fun `execute runs a real command through a real su script and returns its output`() {
        val suPath = writeScript("su", suScriptAvailable())
        val provider = MagiskProvider(suExecutable = suPath)

        val result = provider.execute(RootCommand(executable = "echo", args = listOf("hello-from-root")))

        assertIs<RootExecutionResult.Success>(result)
        assertEquals(0, result.exitCode)
        assertEquals("hello-from-root\n", result.stdout)
    }

    @Test
    fun `execute quotes arguments so shell metacharacters cannot break out of the command`() {
        val suPath = writeScript("su", suScriptAvailable())
        val provider = MagiskProvider(suExecutable = suPath)
        val injectionMarker = File(tempDir, "injection-marker")
        val maliciousArg = "hello; touch ${injectionMarker.absolutePath}; echo done"

        val result = provider.execute(RootCommand(executable = "echo", args = listOf(maliciousArg)))

        assertIs<RootExecutionResult.Success>(result)
        assertEquals("$maliciousArg\n", result.stdout)
        assertFalse(injectionMarker.exists(), "a crafted argument must never be interpreted as a second command")
    }

    // --- RootCommand.workingDirectory / environment (widened alongside ShellCommand) ---

    @Test
    fun `a workingDirectory is applied to the real su process, and inherited by the command it runs`() {
        val suPath = writeScript("su", suScriptAvailable())
        val provider = MagiskProvider(suExecutable = suPath)
        val workDir = File(tempDir, "workdir").apply { mkdir() }

        val result = provider.execute(RootCommand(executable = "pwd", workingDirectory = workDir.absolutePath))

        assertIs<RootExecutionResult.Success>(result)
        assertEquals(workDir.canonicalPath, result.stdout.trim())
    }

    @Test
    fun `environment variables are visible to the real command that actually runs`() {
        val suPath = writeScript("su", suScriptAvailable())
        val provider = MagiskProvider(suExecutable = suPath)

        val result = provider.execute(
            RootCommand(
                executable = "sh",
                args = listOf("-c", "echo \$ROOT_COMMAND_WIDEN_TEST"),
                environment = mapOf("ROOT_COMMAND_WIDEN_TEST" to "hello-from-env"),
            ),
        )

        assertIs<RootExecutionResult.Success>(result)
        assertEquals("hello-from-env\n", result.stdout)
    }

    @Test
    fun `defaults leave workingDirectory null and environment empty, matching the process's own cwd and no extra vars`() {
        val suPath = writeScript("su", suScriptAvailable())
        val provider = MagiskProvider(suExecutable = suPath)

        val result = provider.execute(RootCommand(executable = "pwd"))

        assertIs<RootExecutionResult.Success>(result)
        assertEquals(File(".").canonicalPath, result.stdout.trim())
    }
}
