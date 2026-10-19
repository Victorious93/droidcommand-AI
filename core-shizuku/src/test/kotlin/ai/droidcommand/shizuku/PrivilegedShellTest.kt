package ai.droidcommand.shizuku

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PrivilegedShellTest {
    @Test
    fun `null shell is unavailable and never fabricates success`() {
        val shell = NullPrivilegedShell()
        assertFalse(shell.isAvailable())
        assertIs<PrivilegedResult.Failure>(shell.exec(listOf("id")))
        assertIs<PrivilegedResult.Failure>(shell.readDeviceState())
        assertIs<PrivilegedResult.Failure>(shell.pmGrant("ai.droidcommand.app", "android.permission.INTERNET"))
    }

    @Test
    fun `result codec round-trips success and failure including newlines`() {
        val success = PrivilegedResult.Success(0, "out\nline", "err\nline", timedOut = false)
        assertEquals(success, PrivilegedResultCodec.decode(PrivilegedResultCodec.encode(success)))
        val timedOut = PrivilegedResult.Success(143, "", "", timedOut = true)
        assertEquals(timedOut, PrivilegedResultCodec.decode(PrivilegedResultCodec.encode(timedOut)))
        val failure = PrivilegedResult.Failure("nope\nreally")
        val decoded = assertIs<PrivilegedResult.Failure>(PrivilegedResultCodec.decode(PrivilegedResultCodec.encode(failure)))
        assertEquals("nope\nreally", decoded.reason)
    }

    @Test
    fun `codec rejects garbage without throwing`() {
        assertIs<PrivilegedResult.Failure>(PrivilegedResultCodec.decode(""))
        assertIs<PrivilegedResult.Failure>(PrivilegedResultCodec.decode("nope"))
        assertIs<PrivilegedResult.Failure>(PrivilegedResultCodec.decode("S\nnot-an-int\n0\n\n"))
    }

    @Test
    fun `android names accept packages and permissions and reject injection`() {
        assertTrue(PrivilegedNames.isSafeAndroidName("ai.droidcommand.app"))
        assertTrue(PrivilegedNames.isSafeAndroidName("android.permission.WRITE_SECURE_SETTINGS"))
        assertFalse(PrivilegedNames.isSafeAndroidName("id"))
        assertFalse(PrivilegedNames.isSafeAndroidName("ai.droidcommand.app; rm -rf /"))
        assertFalse(PrivilegedNames.isSafeAndroidName("ai.droidcommand.app && id"))
        assertFalse(PrivilegedNames.isSafeAndroidName(""))
    }

    @Test
    fun `process runner executes argv and refuses an empty vector`() {
        assertIs<PrivilegedResult.Failure>(PrivilegedProcessRunner.run(emptyList(), 5_000))
        val result = assertIs<PrivilegedResult.Success>(PrivilegedProcessRunner.run(listOf("echo", "shizuku-ok"), 5_000))
        assertEquals(0, result.exitCode)
        assertTrue(result.stdout.contains("shizuku-ok"))
        assertFalse(result.timedOut)
    }

    @Test
    fun `pm grant refuses unsafe names before spawning`() {
        val badPkg = assertIs<PrivilegedResult.Failure>(
            PrivilegedProcessRunner.pmGrant("not a package", "android.permission.INTERNET"),
        )
        assertTrue(badPkg.reason.contains("package"))
        val badPerm = PrivilegedProcessRunner.pmGrant("ai.droidcommand.app", "INTERNET")
        assertIs<PrivilegedResult.Failure>(badPerm)
    }
}
