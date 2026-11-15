package ai.droidcommand.app.systemsettings

import ai.droidcommand.app.shizuku.ToolboxRuntime
import ai.droidcommand.security.ApprovalPrompt
import ai.droidcommand.security.JsonFileAuditLog
import ai.droidcommand.systemsettings.AccessKind
import ai.droidcommand.systemsettings.BootReplayCoordinator
import ai.droidcommand.systemsettings.ChangeApprover
import ai.droidcommand.systemsettings.DeviceContext
import ai.droidcommand.systemsettings.FileOperationJournal
import ai.droidcommand.systemsettings.FileProfileStore
import ai.droidcommand.systemsettings.FileReplayStateStore
import ai.droidcommand.systemsettings.ProfileService
import ai.droidcommand.systemsettings.SettingsProfile
import ai.droidcommand.systemsettings.ShellSettingsBackend
import ai.droidcommand.systemsettings.StandardSettingsBackend
import ai.droidcommand.systemsettings.StarterProfiles
import ai.droidcommand.systemsettings.SystemSettingsEngine
import android.content.Context
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the one System Settings engine for this process and hands the same instance to the screen, the assistant's tools and the boot
 * receiver. It reuses the System Toolbox's Shizuku and root transports, the app's approval prompt and the app's audit log.
 *
 * Remote: no remote settings command exists in this app, so the engine is built without a remote authorizer and refuses remote requests.
 */
@Singleton
class SystemSettingsRuntime @Inject constructor(
    @ApplicationContext private val context: Context,
    toolbox: ToolboxRuntime,
    val prefs: SystemSettingsPrefs,
    approvalPrompt: ApprovalPrompt,
    auditLog: JsonFileAuditLog,
) {
    val platform = AndroidPlatformSettings(context)
    val dnsVerifier = PrivateDnsVerifier(context)
    private val root = context.filesDir.toPath().resolve("system-settings")

    fun device(): DeviceContext = DeviceFacts.context(context)

    val engine = SystemSettingsEngine(
        backends = listOf(
            StandardSettingsBackend(platform),
            ShellSettingsBackend(AccessKind.SHIZUKU, toolbox.shizuku),
            ShellSettingsBackend(AccessKind.ROOT, toolbox.root) { "A su binary is present. Your root manager still decides each request." },
        ),
        journal = FileOperationJournal(root.resolve("journal")),
        device = ::device,
        access = prefs::config,
        approver = ChangeApprover { source, title, lines, backend ->
            approvalPrompt.requestApproval(
                toolName = "system_settings_change",
                input = mapOf("requested_by" to source.name, "access" to backend.label, "changes" to lines.joinToString("\n")),
                reason = "System Settings change ($title) using ${backend.label}",
            )
        },
        remote = null,
        verifiers = listOf(dnsVerifier),
        audit = auditLog,
    )

    private val profileStore = FileProfileStore(root.resolve("profiles"))
    val profileService = ProfileService(engine, ::device)

    fun profiles(): List<SettingsProfile> = StarterProfiles.all() + profileStore.list()
    fun findProfile(id: String): SettingsProfile? = profiles().firstOrNull { it.id == id }
    fun saveProfile(profile: SettingsProfile) = profileStore.save(profile)
    fun deleteProfile(id: String): Boolean = profileStore.delete(id)

    val replay = BootReplayCoordinator(engine, profileService, ::findProfile, FileReplayStateStore(root.resolve("replay.json")), ::device)

    /** Reads (never replays) any operation a crash left half-done. Safe to call often. */
    fun recoverInterrupted() = engine.recoverInterrupted()

    fun bootId(): String = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1).toString()
}
