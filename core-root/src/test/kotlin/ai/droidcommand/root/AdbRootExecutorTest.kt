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
 * Proves [AdbRootExecutor]'s detection/execution logic against real, controlled fixtures (a real
 * scripted `adb` shell script, spawned as a real subprocess) rather than a mock — mirroring
 * `MagiskProviderTest`'s "prove the boundary is real" convention. The fixture `adb` script
 * faithfully reproduces the one behavior this class's whole design hinges on: real `adb shell`
 * flattens every trailing argument into one space-joined line before a real device's shell parses
 * it — modeled here with a real `eval` on the joined line, so the same two-layer [shellQuote]ing
 * this class performs is genuinely exercised, not merely assumed correct. Real on-device
 * verification against an actual adb-connected, Magisk-rooted device lives in
 * `AdbRootExecutorRealDeviceIntegrationTest`, which skips cleanly wherever no such device exists —
 * including here, since this environment does not run tests with real hardware attached.
 */
class AdbRootExecutorTest {
    private lateinit var tempDir: File

    @BeforeTest
    fun setUp() {
        tempDir = createTempDirectory("adb-root-executor-test").toFile()
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

    /** Faithfully emulates real `adb`'s `get-state`/`shell <args...>` behavior — see class doc. */
    private fun adbScript(androidVersion: String = "16"): String = """
        #!/bin/sh
        if [ "${'$'}1" = "get-state" ]; then
          echo "device"
          exit 0
        fi
        if [ "${'$'}1" = "shell" ]; then
          shift
          if [ "${'$'}*" = "getprop ro.build.version.release" ]; then
            echo "$androidVersion"
            exit 0
          fi
          eval "${'$'}*"
          exit ${'$'}?
        fi
        exit 1
        """.trimIndent()

    private val adbScriptNoDevice = """
        #!/bin/sh
        exit 1
    """.trimIndent()

    private fun adbScriptRequiringSerial(serial: String): String = """
        #!/bin/sh
        if [ "${'$'}1" != "-s" ] || [ "${'$'}2" != "$serial" ]; then
          exit 1
        fi
        shift 2
        if [ "${'$'}1" = "get-state" ]; then
          echo "device"
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

    // --- isDeviceConnected / getAndroidVersion ---

    @Test
    fun `no device connected when adb reports a non-device state`() {
        val executor = AdbRootExecutor(adbExecutable = writeScript("adb", adbScriptNoDevice))
        assertFalse(executor.isDeviceConnected())
    }

    @Test
    fun `device connected via a real scripted adb reporting 'device'`() {
        val executor = AdbRootExecutor(adbExecutable = writeScript("adb", adbScript()))
        assertTrue(executor.isDeviceConnected())
    }

    @Test
    fun `android version is parsed from the real scripted adb's getprop output`() {
        val executor = AdbRootExecutor(adbExecutable = writeScript("adb", adbScript(androidVersion = "16")))
        assertEquals("16", executor.getAndroidVersion())
    }

    @Test
    fun `android version is null when adb cannot be started`() {
        val executor = AdbRootExecutor(adbExecutable = File(tempDir, "no-such-adb").absolutePath)
        assertNull(executor.getAndroidVersion())
    }

    @Test
    fun `-s serial is threaded into every adb invocation when configured`() {
        val serial = "ABCD1234"
        val executor = AdbRootExecutor(adbExecutable = writeScript("adb", adbScriptRequiringSerial(serial)), serial = serial)
        assertTrue(executor.isDeviceConnected())

        val wrongSerial = AdbRootExecutor(adbExecutable = writeScript("adb2", adbScriptRequiringSerial(serial)), serial = "other")
        assertFalse(wrongSerial.isDeviceConnected())
    }

    // --- isRootAvailable / probe caching ---

    @Test
    fun `root unavailable when adb cannot be started`() {
        val executor = AdbRootExecutor(adbExecutable = File(tempDir, "no-such-adb").absolutePath)
        assertFalse(executor.isRootAvailable())
    }

    @Test
    fun `root available when the real su script reaches root over the joined adb shell line`() {
        val suPath = writeScript("su", suScriptAvailable())
        val executor = AdbRootExecutor(adbExecutable = writeScript("adb", adbScript()), suExecutable = suPath)
        assertTrue(executor.isRootAvailable())
        assertTrue(executor.isAuthorized())
    }

    @Test
    fun `root shell present but denied (non-zero uid) is not treated as available`() {
        val suPath = writeScript("su", suScriptDenied)
        val executor = AdbRootExecutor(adbExecutable = writeScript("adb", adbScript()), suExecutable = suPath)
        assertFalse(executor.isRootAvailable())
    }

    @Test
    fun `probe result is cached within the TTL, not re-run on every call`() {
        val counterFile = File(tempDir, "calls.log")
        val suPath = writeScript("su", suScriptAvailable(counterFile))
        val executor = AdbRootExecutor(adbExecutable = writeScript("adb", adbScript()), suExecutable = suPath, cacheTtlMillis = 60_000)

        executor.isRootAvailable()
        executor.isRootAvailable()
        executor.getPrivilegeLevel()
        assertEquals(1, counterFile.readLines().size)

        executor.invalidateCache()
        executor.isRootAvailable()
        assertEquals(2, counterFile.readLines().size)
    }

    // --- getPrivilegeLevel ---

    @Test
    fun `privilege level is NONE when no device is connected`() {
        val executor = AdbRootExecutor(adbExecutable = writeScript("adb", adbScriptNoDevice))
        assertEquals(PrivilegeLevel.NONE, executor.getPrivilegeLevel())
    }

    @Test
    fun `privilege level is USER when a device is connected but root is denied`() {
        val executor = AdbRootExecutor(adbExecutable = writeScript("adb", adbScript()), suExecutable = writeScript("su", suScriptDenied))
        assertEquals(PrivilegeLevel.USER, executor.getPrivilegeLevel())
    }

    @Test
    fun `privilege level is ROOT when root is genuinely functional`() {
        val executor = AdbRootExecutor(adbExecutable = writeScript("adb", adbScript()), suExecutable = writeScript("su", suScriptAvailable()))
        assertEquals(PrivilegeLevel.ROOT, executor.getPrivilegeLevel())
    }

    // --- checkHealth ---

    @Test
    fun `health is UNAVAILABLE when no device is connected, regardless of probeShell`() {
        val executor = AdbRootExecutor(adbExecutable = writeScript("adb", adbScriptNoDevice))
        for (probeShell in listOf(false, true)) {
            val health = executor.checkHealth(probeShell = probeShell)
            assertEquals(RootProviderState.UNAVAILABLE, health.state)
            assertTrue(health.lastError!!.contains("No authorized adb device"))
        }
    }

    @Test
    fun `health with probeShell=false never spawns su, even when a device is connected`() {
        val counterFile = File(tempDir, "calls.log")
        val executor = AdbRootExecutor(adbExecutable = writeScript("adb", adbScript()), suExecutable = writeScript("su", suScriptAvailable(counterFile)))

        val health = executor.checkHealth(probeShell = false)

        assertEquals(RootProviderState.REQUIRES_PERMISSION, health.state)
        assertTrue(health.lastError!!.contains("not probed"))
        assertFalse(counterFile.exists(), "checkHealth(probeShell = false) must never spawn su")
    }

    @Test
    fun `health with probeShell=true reports AVAILABLE when root is genuinely functional`() {
        val executor = AdbRootExecutor(adbExecutable = writeScript("adb", adbScript()), suExecutable = writeScript("su", suScriptAvailable()))

        val health = executor.checkHealth(probeShell = true)

        assertEquals(RootProviderState.AVAILABLE, health.state)
        assertTrue(health.rootAvailable)
        assertEquals(PrivilegeLevel.ROOT, health.privilegeLevel)
        assertNull(health.lastError)
    }

    @Test
    fun `health with probeShell=true reports REQUIRES_PERMISSION when denied`() {
        val executor = AdbRootExecutor(adbExecutable = writeScript("adb", adbScript()), suExecutable = writeScript("su", suScriptDenied))

        val health = executor.checkHealth(probeShell = true)

        assertEquals(RootProviderState.REQUIRES_PERMISSION, health.state)
        assertFalse(health.rootAvailable)
        assertTrue(health.lastError!!.isNotBlank())
    }

    // --- getCapabilities ---

    @Test
    fun `no capabilities reported when no device is connected`() {
        val executor = AdbRootExecutor(adbExecutable = writeScript("adb", adbScriptNoDevice))
        assertTrue(executor.getCapabilities().isEmpty())
    }

    @Test
    fun `capabilities include the android version query when it resolves`() {
        val executor = AdbRootExecutor(adbExecutable = writeScript("adb", adbScript()))
        val caps = executor.getCapabilities()
        assertTrue("adb_device_detection" in caps)
        assertTrue("adb_root_shell_execute" in caps)
        assertTrue("adb_android_version_query" in caps)
    }

    // --- execute (RootExecutor contract) ---

    @Test
    fun `execute fails explicitly when adb cannot be started`() {
        val executor = AdbRootExecutor(adbExecutable = File(tempDir, "no-such-adb").absolutePath)
        val result = executor.execute(RootCommand(executable = "id"))
        assertIs<RootExecutionResult.Failure>(result)
        assertTrue(result.reason.contains("Failed to start"))
    }

    @Test
    fun `execute runs a real command through the real joined adb shell line and returns its output`() {
        val executor = AdbRootExecutor(adbExecutable = writeScript("adb", adbScript()), suExecutable = writeScript("su", suScriptAvailable()))

        val result = executor.execute(RootCommand(executable = "echo", args = listOf("hello-from-adb-root")))

        assertIs<RootExecutionResult.Success>(result)
        assertEquals(0, result.exitCode)
        assertEquals("hello-from-adb-root\n", result.stdout)
    }

    @Test
    fun `execute double-quotes so a crafted argument survives both the adb join and the su shell parse`() {
        val executor = AdbRootExecutor(adbExecutable = writeScript("adb", adbScript()), suExecutable = writeScript("su", suScriptAvailable()))
        val injectionMarker = File(tempDir, "injection-marker")
        val maliciousArg = "hello; touch ${injectionMarker.absolutePath}; echo done"

        val result = executor.execute(RootCommand(executable = "echo", args = listOf(maliciousArg)))

        assertIs<RootExecutionResult.Success>(result)
        assertEquals("$maliciousArg\n", result.stdout)
        assertFalse(injectionMarker.exists(), "a crafted argument must never be interpreted as a second command")
    }

    @Test
    fun `a workingDirectory is folded into the remote command line via cd, not the local adb process`() {
        val executor = AdbRootExecutor(adbExecutable = writeScript("adb", adbScript()), suExecutable = writeScript("su", suScriptAvailable()))
        val workDir = File(tempDir, "workdir").apply { mkdir() }

        val result = executor.execute(RootCommand(executable = "pwd", workingDirectory = workDir.absolutePath))

        assertIs<RootExecutionResult.Success>(result)
        assertEquals(workDir.canonicalPath, result.stdout.trim())
    }

    @Test
    fun `environment variables are folded into the remote command line and visible to the command`() {
        val executor = AdbRootExecutor(adbExecutable = writeScript("adb", adbScript()), suExecutable = writeScript("su", suScriptAvailable()))

        val result = executor.execute(
            RootCommand(
                executable = "sh",
                args = listOf("-c", "echo \$ADB_ROOT_EXECUTOR_TEST"),
                environment = mapOf("ADB_ROOT_EXECUTOR_TEST" to "hello-from-env"),
            ),
        )

        assertIs<RootExecutionResult.Success>(result)
        assertEquals("hello-from-env\n", result.stdout)
    }
}
