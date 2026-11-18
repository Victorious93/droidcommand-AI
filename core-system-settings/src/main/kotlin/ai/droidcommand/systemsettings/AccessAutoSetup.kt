package ai.droidcommand.systemsettings

import ai.droidcommand.shizuku.PrivilegedNames
import ai.droidcommand.shizuku.PrivilegedResult
import ai.droidcommand.shizuku.toolbox.ToolboxBackend
import ai.droidcommand.shizuku.toolbox.ToolboxCommand

/**
 * Grants this app the two Android permissions the Standard access method needs, using Shizuku (ADB shell user) or root, so the owner does
 * not have to type `adb shell pm grant ...` from a computer.
 *
 * Only these two grants are ever issued, and only with the app's own package name:
 *  - `pm grant <app> android.permission.WRITE_SECURE_SETTINGS` (Secure and Global tables)
 *  - `appops set <app> WRITE_SETTINGS allow` (System table)
 *
 * Which backend may be used is the caller's decision ([allowed] comes from the owner's Access switches); Shizuku is tried before root and
 * root is never used unless the owner turned it on. Nothing here retries or escalates beyond that order.
 */
class AccessAutoSetup(
    private val shizuku: ToolboxBackend,
    private val root: ToolboxBackend,
) {
    enum class Grant(val label: String) {
        SECURE_SETTINGS("Secure and Global tables (WRITE_SECURE_SETTINGS)"),
        SYSTEM_SETTINGS("System table (Modify system settings)"),
    }

    data class Report(
        val via: AccessKind?,
        val granted: List<Grant>,
        val failed: Map<Grant, String>,
        val note: String?,
    ) {
        val ok: Boolean get() = via != null && failed.isEmpty() && note == null
    }

    /** The backend that would be used right now, or null when neither an allowed Shizuku nor an allowed root is available. */
    fun backendFor(allowed: Set<AccessKind>): Pair<AccessKind, ToolboxBackend>? = when {
        AccessKind.SHIZUKU in allowed && shizuku.isAvailable() -> AccessKind.SHIZUKU to shizuku
        AccessKind.ROOT in allowed && root.isAvailable() -> AccessKind.ROOT to root
        else -> null
    }

    /** Which grants the standard backend's [status] says are still missing. */
    fun missing(standard: BackendStatus?): Set<Grant> {
        if (standard == null) return emptySet()
        return buildSet {
            if (SettingsTable.SECURE !in standard.writableTables || SettingsTable.GLOBAL !in standard.writableTables) add(Grant.SECURE_SETTINGS)
            if (SettingsTable.SYSTEM !in standard.writableTables) add(Grant.SYSTEM_SETTINGS)
        }
    }

    fun run(applicationId: String, grants: Set<Grant>, allowed: Set<AccessKind>): Report {
        if (grants.isEmpty()) return Report(null, emptyList(), emptyMap(), null)
        if (!PrivilegedNames.isSafeAndroidName(applicationId)) {
            return Report(null, emptyList(), emptyMap(), "Invalid package name.")
        }
        val (kind, backend) = backendFor(allowed)
            ?: return Report(null, emptyList(), emptyMap(), "Neither Shizuku nor root is available and turned on, so this cannot be set up automatically.")
        val granted = mutableListOf<Grant>()
        val failed = linkedMapOf<Grant, String>()
        for (grant in Grant.entries.filter { it in grants }) {
            when (val r = backend.run(commandFor(grant, applicationId))) {
                is PrivilegedResult.Failure -> failed[grant] = r.reason.take(MAX_MESSAGE)
                is PrivilegedResult.Success -> when {
                    r.timedOut -> failed[grant] = "${kind.label} did not answer in time."
                    r.exitCode != 0 -> failed[grant] = r.stderr.ifBlank { r.stdout }.trim().take(MAX_MESSAGE).ifBlank { "The command failed (exit ${r.exitCode})." }
                    else -> granted += grant
                }
            }
        }
        return Report(kind, granted, failed, null)
    }

    companion object {
        const val SECURE_PERMISSION = "android.permission.WRITE_SECURE_SETTINGS"
        private const val MAX_MESSAGE = 200
        private const val TIMEOUT_MS = 15_000L

        fun commandFor(grant: Grant, applicationId: String): ToolboxCommand = when (grant) {
            Grant.SECURE_SETTINGS -> ToolboxCommand("Grant WRITE_SECURE_SETTINGS", listOf("pm", "grant", applicationId, SECURE_PERMISSION), destructive = true, timeoutMs = TIMEOUT_MS)
            Grant.SYSTEM_SETTINGS -> ToolboxCommand("Allow Modify system settings", listOf("appops", "set", applicationId, "WRITE_SETTINGS", "allow"), destructive = true, timeoutMs = TIMEOUT_MS)
        }
    }
}
