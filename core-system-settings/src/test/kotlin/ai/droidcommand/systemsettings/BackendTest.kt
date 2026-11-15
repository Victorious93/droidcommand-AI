package ai.droidcommand.systemsettings

import ai.droidcommand.shizuku.PrivilegedResult
import ai.droidcommand.shizuku.toolbox.RootToolboxBackend
import ai.droidcommand.shizuku.toolbox.ToolboxBackend
import ai.droidcommand.shizuku.toolbox.ToolboxCommand
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A toolbox transport that answers from a script and records every argv it was given. */
class ScriptedToolbox(
    override val isRoot: Boolean = false,
    var available: Boolean = true,
    val respond: (List<String>) -> PrivilegedResult,
) : ToolboxBackend {
    override val name = if (isRoot) "Root" else "Shizuku"
    val calls = mutableListOf<List<String>>()
    override fun isAvailable() = available
    override fun display(command: ToolboxCommand) = command.display
    override fun run(command: ToolboxCommand): PrivilegedResult {
        calls += command.argv
        return respond(command.argv)
    }
}

private fun ok(out: String = "", err: String = "", code: Int = 0) = PrivilegedResult.Success(code, out, err, false)

class ShellBackendTest {
    private val key = SettingKey.of(SettingsTable.GLOBAL, "window_animation_scale", 0)

    @Test
    fun absentIsToldApartFromEmptyAndFromTheTextNull() {
        val tb = ScriptedToolbox { argv ->
            when {
                argv.contains("get") && argv.last() == "absent_key" -> ok("null\n")
                argv.contains("get") && argv.last() == "empty_key" -> ok("\n")
                argv.contains("get") && argv.last() == "text_null" -> ok("null\n")
                argv.contains("get") -> ok("1.0\n")
                argv.contains("list") -> ok("text_null=null\nother=1\n")
                else -> ok()
            }
        }
        val b = ShellSettingsBackend(AccessKind.SHIZUKU, tb)
        fun read(n: String) = (b.read(SettingKey.of(SettingsTable.GLOBAL, n, 0)) as ReadResult.Value).snapshot
        assertEquals(Snapshot.Absent, read("absent_key"))
        assertEquals(Snapshot.Present(""), read("empty_key"))
        assertEquals(Snapshot.Present("null"), read("text_null"))
        assertEquals(Snapshot.Present("1.0"), read("window_animation_scale"))
    }

    @Test
    fun explicitUserIsAlwaysPassedBecauseTheShellDefaultIsTheSystemUser() {
        val tb = ScriptedToolbox { ok("1\n") }
        val b = ShellSettingsBackend(AccessKind.SHIZUKU, tb)
        b.read(SettingKey.of(SettingsTable.SECURE, "x", 10))
        b.write(SettingKey.of(SettingsTable.SYSTEM, "y", 10), "2")
        b.read(SettingKey.of(SettingsTable.GLOBAL, "g", 10)) // global is device-wide: user 0
        assertEquals(listOf("settings", "--user", "10", "get", "secure", "x"), tb.calls[0])
        assertEquals(listOf("settings", "--user", "10", "put", "system", "y", "2"), tb.calls[1])
        assertEquals(listOf("settings", "--user", "0", "get", "global", "g"), tb.calls[2])
    }

    @Test
    fun deadServiceTimeoutAndPermissionAreDifferentFailures() {
        val tb = ScriptedToolbox { ok() }
        val b = ShellSettingsBackend(AccessKind.SHIZUKU, tb)
        tb.available = false
        assertEquals(FailureKind.UNAVAILABLE, (b.read(key) as ReadResult.Failed).failure.kind)
        assertEquals(BackendState.UNAVAILABLE, b.status().state)
        tb.available = true
        val timeout = ScriptedToolbox { PrivilegedResult.Success(-1, "", "", true) }
        assertEquals(FailureKind.TIMEOUT, (ShellSettingsBackend(AccessKind.SHIZUKU, timeout).write(key, "1") as WriteResult.Failed).failure.kind)
        val denied = ScriptedToolbox { ok("", "java.lang.SecurityException: Permission denial", 1) }
        assertEquals(FailureKind.PERMISSION_DENIED, (ShellSettingsBackend(AccessKind.SHIZUKU, denied).write(key, "1") as WriteResult.Failed).failure.kind)
        val failure = ScriptedToolbox { PrivilegedResult.Failure("service died") }
        assertEquals(FailureKind.ERROR, (ShellSettingsBackend(AccessKind.SHIZUKU, failure).write(key, "1") as WriteResult.Failed).failure.kind)
        val vanishing = ScriptedToolbox { PrivilegedResult.Failure("binder died") }
        val vb = ShellSettingsBackend(AccessKind.SHIZUKU, vanishing)
        vanishing.available = true
        val r = vb.read(key)
        assertIs<ReadResult.Failed>(r)
    }

    @Test
    fun multilineAndControlCharacterValuesAreRefusedBeforeAnyCommand() {
        val tb = ScriptedToolbox { ok() }
        val b = ShellSettingsBackend(AccessKind.SHIZUKU, tb)
        assertEquals(FailureKind.REJECTED, (b.write(key, "a\nb") as WriteResult.Failed).failure.kind)
        assertEquals(FailureKind.REJECTED, (b.write(key, "a\u0000b") as WriteResult.Failed).failure.kind)
        assertEquals(FailureKind.REJECTED, (b.write(key, "x".repeat(5000)) as WriteResult.Failed).failure.kind)
        assertTrue(tb.calls.isEmpty())
    }

    @Test
    fun listParsingFlagsLinesItCannotAttribute() {
        val tb = ScriptedToolbox { ok("a=1\nb=\ncontinued line without equals\nc=x=y\n") }
        val r = ShellSettingsBackend(AccessKind.ROOT, tb).list(SettingsTable.GLOBAL, 0) as ListResult.Entries
        assertEquals(mapOf("a" to "1", "b" to "", "c" to "x=y"), r.values)
        assertTrue(r.partial)
    }

    /** The root path joins a string for `su -c`; hostile values must still reach `settings` as ONE literal argument. */
    @Test
    fun hostileValuesStayOneLiteralArgumentThroughRealShellQuoting() {
        val dir = Files.createTempDirectory("fakebin")
        val log = dir.resolve("args.log")
        val pwned = dir.resolve("PWNED")
        val script = dir.resolve("settings")
        Files.writeString(script, "#!/bin/sh\nfor a in \"\$@\"; do printf '%s\\n' \"\$a\" >> '$log'; done\n")
        script.toFile().setExecutable(true)
        val runner = { argv: List<String>, _: Long ->
            val pb = ProcessBuilder("sh", "-c", argv[2])
            pb.environment()["PATH"] = dir.toString() + ":" + System.getenv("PATH")
            val p = pb.start()
            val out = p.inputStream.readBytes().decodeToString()
            val err = p.errorStream.readBytes().decodeToString()
            PrivilegedResult.Success(p.waitFor(), out, err, false)
        }
        val backend = ShellSettingsBackend(AccessKind.ROOT, RootToolboxBackend({ true }, runner))
        val hostile = listOf(
            "x'; touch '$pwned'; echo '",
            "\$(touch '$pwned')",
            "`touch '$pwned'`",
            "a && touch '$pwned'",
            "a | tee '$pwned'",
            "--user 99 put global y z",
            "with space and \"double\" and 'single'",
        )
        for (v in hostile) {
            Files.deleteIfExists(log)
            val r = backend.write(SettingKey.of(SettingsTable.GLOBAL, "dca_inject", 0), v)
            assertEquals(WriteResult.Done, r, v)
            assertEquals(listOf("--user", "0", "put", "global", "dca_inject", v), Files.readAllLines(log), "argv for: $v")
            assertFalse(Files.exists(pwned), "shell fragment ran for: $v")
        }
    }
}

class StandardBackendTest {
    private class Platform(val user: Int = 0) : PlatformSettingsAccess {
        override val currentUserId = user
        val rows = mutableMapOf<String, String>()
        var canWriteTables = SettingsTable.entries.toSet()
        var throwOnGet: RuntimeException? = null
        var putReturns = true
        override fun getString(table: SettingsTable, name: String): String? {
            throwOnGet?.let { throw it }
            return rows["${table.wire}/$name"]
        }
        override fun listAll(table: SettingsTable): Map<String, String> = throw SecurityException("no")
        override fun putString(table: SettingsTable, name: String, value: String): Boolean {
            if (putReturns) rows["${table.wire}/$name"] = value
            return putReturns
        }
        override fun delete(table: SettingsTable, name: String): Int = if (rows.remove("${table.wire}/$name") != null) 1 else 0
        override fun canWrite(table: SettingsTable) = table in canWriteTables
        override fun missingPermissionHint(table: SettingsTable) = "grant it for ${table.wire}"
    }

    @Test
    fun nullIsAbsentEmptyIsPresentAndASecurityExceptionIsUnreadableNotAbsent() {
        val p = Platform()
        val b = StandardSettingsBackend(p)
        val k = SettingKey.of(SettingsTable.SYSTEM, "k", 0)
        assertEquals(Snapshot.Absent, (b.read(k) as ReadResult.Value).snapshot)
        p.rows["system/k"] = ""
        assertEquals(Snapshot.Present(""), (b.read(k) as ReadResult.Value).snapshot)
        p.throwOnGet = SecurityException("not readable")
        assertEquals(FailureKind.UNREADABLE, (b.read(k) as ReadResult.Failed).failure.kind)
    }

    @Test
    fun missingPermissionBlocksWritesAndStatusSaysWhichTable() {
        val p = Platform().also { it.canWriteTables = setOf(SettingsTable.SYSTEM) }
        val b = StandardSettingsBackend(p)
        val s = b.status()
        assertEquals(BackendState.PERMISSION_MISSING, s.state)
        assertEquals(setOf(SettingsTable.SYSTEM), s.writableTables)
        assertTrue(s.detail.contains("secure") && s.detail.contains("global"))
        assertEquals(FailureKind.PERMISSION_DENIED, (b.write(SettingKey.of(SettingsTable.GLOBAL, "g", 0), "1") as WriteResult.Failed).failure.kind)
        assertEquals(WriteResult.Done, b.write(SettingKey.of(SettingsTable.SYSTEM, "s", 0), "1"))
    }

    @Test
    fun providerThatDoesNotStoreIsRejectedAndOtherUsersAreOutOfReach() {
        val p = Platform(user = 0)
        val b = StandardSettingsBackend(p)
        p.putReturns = false
        assertEquals(FailureKind.REJECTED, (b.write(SettingKey.of(SettingsTable.SYSTEM, "s", 0), "1") as WriteResult.Failed).failure.kind)
        val other = SettingKey.of(SettingsTable.SYSTEM, "s", 10)
        assertEquals(FailureKind.UNSUPPORTED, (b.read(other) as ReadResult.Failed).failure.kind)
        assertEquals(FailureKind.UNSUPPORTED, (b.write(other, "1") as WriteResult.Failed).failure.kind)
        // Global is device-wide, so it is always in reach.
        assertEquals(Snapshot.Absent, (b.read(SettingKey.of(SettingsTable.GLOBAL, "g", 10)) as ReadResult.Value).snapshot)
        assertEquals(FailureKind.PERMISSION_DENIED, (b.list(SettingsTable.GLOBAL, 0) as ListResult.Failed).failure.kind)
    }
}
