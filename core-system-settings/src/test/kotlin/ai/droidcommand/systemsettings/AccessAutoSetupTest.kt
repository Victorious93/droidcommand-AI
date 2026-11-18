package ai.droidcommand.systemsettings

import ai.droidcommand.security.AuditEvent
import ai.droidcommand.security.AuditEventType
import ai.droidcommand.security.AuditLog
import ai.droidcommand.security.InMemoryAuditLog
import ai.droidcommand.shizuku.PrivilegedResult
import ai.droidcommand.shizuku.toolbox.ToolboxBackend
import ai.droidcommand.shizuku.toolbox.ToolboxCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccessAutoSetupTest {
    private class Fake(override val name: String, override val isRoot: Boolean, var up: Boolean, val exit: (List<String>) -> Int = { 0 }) : ToolboxBackend {
        val ran = mutableListOf<List<String>>()
        override fun isAvailable() = up
        override fun display(command: ToolboxCommand) = command.display
        override fun run(command: ToolboxCommand): PrivilegedResult {
            ran += command.argv
            val code = exit(command.argv)
            return PrivilegedResult.Success(code, "", if (code == 0) "" else "Exception occurred: SecurityException", false)
        }
    }

    private val app = "ai.droidcommand.app"
    private val both = AccessAutoSetup.Grant.entries.toSet()

    @Test
    fun shizukuGrantsBothPermissionsWithExactArgv() {
        val shizuku = Fake("Shizuku", false, true)
        val root = Fake("Root", true, true)
        val report = AccessAutoSetup(shizuku, root).run(app, both, setOf(AccessKind.STANDARD, AccessKind.SHIZUKU))
        assertTrue(report.ok)
        assertEquals(AccessKind.SHIZUKU, report.via)
        assertEquals(
            listOf(
                listOf("pm", "grant", app, "android.permission.WRITE_SECURE_SETTINGS"),
                listOf("appops", "set", app, "WRITE_SETTINGS", "allow"),
            ),
            shizuku.ran,
        )
        assertTrue(root.ran.isEmpty())
    }

    @Test
    fun rootIsNeverUsedUnlessTheOwnerAllowedIt() {
        val root = Fake("Root", true, true)
        val setup = AccessAutoSetup(Fake("Shizuku", false, false), root)
        assertNull(setup.backendFor(setOf(AccessKind.STANDARD, AccessKind.SHIZUKU)))
        val report = setup.run(app, both, setOf(AccessKind.STANDARD, AccessKind.SHIZUKU))
        assertFalse(report.ok)
        assertTrue(root.ran.isEmpty())
        assertEquals(AccessKind.ROOT, setup.backendFor(setOf(AccessKind.ROOT))?.first)
    }

    @Test
    fun shizukuIsPreferredOverRootWhenBothAreAllowed() {
        val setup = AccessAutoSetup(Fake("Shizuku", false, true), Fake("Root", true, true))
        assertEquals(AccessKind.SHIZUKU, setup.backendFor(setOf(AccessKind.SHIZUKU, AccessKind.ROOT))?.first)
    }

    @Test
    fun aFailedGrantIsReportedAndTheOtherStillRuns() {
        val shizuku = Fake("Shizuku", false, true, exit = { if (it[0] == "pm") 1 else 0 })
        val report = AccessAutoSetup(shizuku, Fake("Root", true, false)).run(app, both, setOf(AccessKind.SHIZUKU))
        assertFalse(report.ok)
        assertEquals(listOf(AccessAutoSetup.Grant.SYSTEM_SETTINGS), report.granted)
        assertTrue(report.failed.getValue(AccessAutoSetup.Grant.SECURE_SETTINGS).contains("SecurityException"))
    }

    @Test
    fun onlyTheMissingGrantsAreRun() {
        val shizuku = Fake("Shizuku", false, true)
        AccessAutoSetup(shizuku, Fake("Root", true, false)).run(app, setOf(AccessAutoSetup.Grant.SYSTEM_SETTINGS), setOf(AccessKind.SHIZUKU))
        assertEquals(listOf(listOf("appops", "set", app, "WRITE_SETTINGS", "allow")), shizuku.ran)
    }

    @Test
    fun unsafePackageNameIsRefusedBeforeAnythingRuns() {
        val shizuku = Fake("Shizuku", false, true)
        val report = AccessAutoSetup(shizuku, Fake("Root", true, false)).run("x; reboot", both, setOf(AccessKind.SHIZUKU))
        assertFalse(report.ok)
        assertTrue(shizuku.ran.isEmpty())
    }

    @Test
    fun missingIsDerivedFromTheStandardStatus() {
        val setup = AccessAutoSetup(Fake("Shizuku", false, true), Fake("Root", true, false))
        fun status(writable: Set<SettingsTable>) = BackendStatus(AccessKind.STANDARD, BackendState.PERMISSION_MISSING, "", SettingsTable.entries.toSet(), writable, emptySet(), false)
        assertEquals(both, setup.missing(status(emptySet())))
        assertEquals(setOf(AccessAutoSetup.Grant.SECURE_SETTINGS), setup.missing(status(setOf(SettingsTable.SYSTEM))))
        assertTrue(setup.missing(status(SettingsTable.entries.toSet())).isEmpty())
        assertTrue(setup.missing(null).isEmpty())
    }

    @Test
    fun everyGrantIsAuditedBeforeAndAfterItRuns() {
        val audit = InMemoryAuditLog()
        val shizuku = Fake("Shizuku", false, true, exit = { if (it[0] == "pm") 1 else 0 })
        AccessAutoSetup(shizuku, Fake("Root", true, false), audit).run(app, both, setOf(AccessKind.SHIZUKU))
        val types = audit.all().map { it.type }
        assertEquals(
            listOf(AuditEventType.SELF_GRANT_REQUESTED, AuditEventType.SELF_GRANT_REQUESTED, AuditEventType.SELF_GRANT_DONE, AuditEventType.SELF_GRANT_FAILED),
            types,
        )
        assertTrue(audit.all().first().detail.contains("pm grant $app android.permission.WRITE_SECURE_SETTINGS"))
        assertTrue(audit.all().none { it.detail.contains("SecurityException") && it.type == AuditEventType.SELF_GRANT_DONE })
    }

    @Test
    fun aRefusedAuditEntryStopsTheGrantFromRunning() {
        val full = object : AuditLog {
            override fun record(event: AuditEvent) = false
        }
        val shizuku = Fake("Shizuku", false, true)
        val report = AccessAutoSetup(shizuku, Fake("Root", true, false), full).run(app, both, setOf(AccessKind.SHIZUKU))
        assertTrue(shizuku.ran.isEmpty())
        assertTrue(report.granted.isEmpty())
        assertEquals(both, report.failed.keys)
    }

    @Test
    fun nothingIsAuditedWhenNoBackendIsAvailable() {
        val audit = InMemoryAuditLog()
        AccessAutoSetup(Fake("Shizuku", false, false), Fake("Root", true, true), audit).run(app, both, setOf(AccessKind.SHIZUKU))
        assertTrue(audit.all().isEmpty())
    }
}
