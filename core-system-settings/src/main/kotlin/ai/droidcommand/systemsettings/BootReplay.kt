package ai.droidcommand.systemsettings

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

enum class ReplayStatus {
    ARMED,
    UP_TO_DATE,
    APPLIED,

    /** No allowed access method was ready at boot. Shown as pending; retried only at the next boot. */
    PENDING_BACKEND_UNAVAILABLE,
    FAILED,
    SUSPENDED_REPEATED_FAILURES,
    SUSPENDED_CONTEXT_CHANGED,
    SUSPENDED_PROFILE_CHANGED,
    SUSPENDED_PROFILE_MISSING,
    ;

    val suspended: Boolean get() = name.startsWith("SUSPENDED")
}

/** Per-profile reapply state. Off unless the owner enabled it; the context fields are what the owner reviewed when enabling. */
data class ReplayRecord(
    val profileId: String,
    val enabled: Boolean,
    val includeOptional: Boolean,
    val reviewedFingerprint: String,
    val reviewedSdk: Int,
    val reviewedProfileHash: String,
    val status: ReplayStatus,
    val consecutiveFailures: Int = 0,
    val attemptsThisBoot: Int = 0,
    val lastBootId: String? = null,
    val lastAttemptAt: Long? = null,
    val lastOutcome: String = "",
    val enabledAt: Long = 0,
)

interface ReplayStateStore {
    fun all(): List<ReplayRecord>
    fun get(profileId: String): ReplayRecord?

    @Throws(IOException::class)
    fun save(record: ReplayRecord)
    fun remove(profileId: String)
}

class InMemoryReplayStateStore : ReplayStateStore {
    private val map = LinkedHashMap<String, ReplayRecord>()

    @Synchronized override fun all() = map.values.toList()

    @Synchronized override fun get(profileId: String) = map[profileId]

    @Synchronized
    override fun save(record: ReplayRecord) {
        map[record.profileId] = record
    }

    @Synchronized
    override fun remove(profileId: String) {
        map.remove(profileId)
    }
}

@Serializable
private data class ReplayFile(val version: Int = 1, val records: List<ReplayDto> = emptyList())

@Serializable
private data class ReplayDto(
    val profileId: String,
    val enabled: Boolean,
    val includeOptional: Boolean,
    val reviewedFingerprint: String,
    val reviewedSdk: Int,
    val reviewedProfileHash: String,
    val status: String,
    val consecutiveFailures: Int,
    val attemptsThisBoot: Int,
    val lastBootId: String? = null,
    val lastAttemptAt: Long? = null,
    val lastOutcome: String = "",
    val enabledAt: Long = 0,
)

/** One small JSON file, replaced atomically. Lives in credential-encrypted storage, so it is unreadable until the first unlock after boot. */
class FileReplayStateStore(private val file: Path) : ReplayStateStore {
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    override fun all(): List<ReplayRecord> {
        if (!Files.isRegularFile(file)) return emptyList()
        return try {
            json.decodeFromString(ReplayFile.serializer(), file.readUtf8()).records.mapNotNull { d ->
                runCatching {
                    ReplayRecord(
                        d.profileId, d.enabled, d.includeOptional, d.reviewedFingerprint, d.reviewedSdk, d.reviewedProfileHash,
                        ReplayStatus.valueOf(d.status), d.consecutiveFailures, d.attemptsThisBoot, d.lastBootId, d.lastAttemptAt, d.lastOutcome, d.enabledAt,
                    )
                }.getOrNull()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized override fun get(profileId: String) = all().firstOrNull { it.profileId == profileId }

    @Synchronized
    override fun save(record: ReplayRecord) = write(all().filter { it.profileId != record.profileId } + record)

    @Synchronized
    override fun remove(profileId: String) = write(all().filter { it.profileId != profileId })

    private fun write(records: List<ReplayRecord>) {
        Files.createDirectories(file.parent)
        val dto = ReplayFile(
            1,
            records.map {
                ReplayDto(
                    it.profileId, it.enabled, it.includeOptional, it.reviewedFingerprint, it.reviewedSdk, it.reviewedProfileHash, it.status.name,
                    it.consecutiveFailures, it.attemptsThisBoot, it.lastBootId, it.lastAttemptAt, it.lastOutcome, it.enabledAt,
                )
            },
        )
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        tmp.writeUtf8(json.encodeToString(ReplayFile.serializer(), dto))
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
    }
}

data class ReplayRun(val profileId: String, val status: ReplayStatus, val message: String)

/**
 * Optional reapply after reboot. Off by default and set per profile. At boot it runs each enabled profile at most [maxAttemptsPerBoot] times,
 * never loops, never elevates, never regrants a permission and does not change the recorded original baseline. If no allowed backend is ready
 * the profile shows as pending. After [suspendAfterFailures] failures in a row, or when the firmware, Android version or profile contents differ
 * from what the owner reviewed, it suspends until the owner reviews and re-enables it.
 */
class BootReplayCoordinator(
    private val engine: SystemSettingsEngine,
    private val profileService: ProfileService,
    private val lookup: (String) -> SettingsProfile?,
    private val store: ReplayStateStore,
    private val device: () -> DeviceContext,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxAttemptsPerBoot: Int = 2,
    private val suspendAfterFailures: Int = 3,
) {
    /** What access this profile needs and what would be used today, for the owner to read before enabling. */
    fun requirement(profile: SettingsProfile, includeOptional: Boolean): String {
        val dev = device()
        val keys = profile.entries.filter { includeOptional || !it.optional }.map { SettingKey.of(it.table, it.name, dev.userId) }
        if (keys.isEmpty()) return "Nothing to apply."
        val tables = keys.map { it.table }.toSet()
        return "Writes ${tables.joinToString { it.wire }}. " + when (val s = engine.select(tables, write = true, userIds = keys.map { it.userId }.toSet())) {
            is Selection.Chosen -> "Today this would use ${s.backend.kind.label}. If it is not ready at boot, the profile shows as pending and nothing else is tried."
            is Selection.None -> "No allowed access method can do this right now (${s.text}). It would show as pending at boot."
        }
    }

    fun enable(profile: SettingsProfile, includeOptional: Boolean): ReplayRecord {
        val dev = device()
        val rec = ReplayRecord(
            profileId = profile.id, enabled = true, includeOptional = includeOptional, reviewedFingerprint = dev.fingerprint, reviewedSdk = dev.sdkInt,
            reviewedProfileHash = profile.contentHash, status = ReplayStatus.ARMED, enabledAt = clock(),
        )
        store.save(rec)
        return rec
    }

    fun disable(profileId: String) = store.remove(profileId)

    fun records(): List<ReplayRecord> = store.all()

    fun runAfterBoot(bootId: String): List<ReplayRun> {
        val out = mutableListOf<ReplayRun>()
        for (rec0 in store.all().filter { it.enabled }) {
            var rec = rec0
            fun finish(status: ReplayStatus, message: String, failure: Boolean = false, resetFailures: Boolean = false): ReplayRun {
                val fails = when {
                    failure -> rec.consecutiveFailures + 1
                    resetFailures -> 0
                    else -> rec.consecutiveFailures
                }
                val finalStatus = if (failure && fails >= suspendAfterFailures) ReplayStatus.SUSPENDED_REPEATED_FAILURES else status
                rec = rec.copy(status = finalStatus, consecutiveFailures = fails, lastOutcome = message)
                runCatching { store.save(rec) }
                return ReplayRun(rec.profileId, finalStatus, if (finalStatus != status) "Suspended after $fails failures in a row. $message" else message)
            }
            if (rec.status.suspended) {
                out += ReplayRun(rec.profileId, rec.status, "Suspended until you review it. ${rec.lastOutcome}")
                continue
            }
            val profile = lookup(rec.profileId)
            if (profile == null) {
                out += finish(ReplayStatus.SUSPENDED_PROFILE_MISSING, "The profile no longer exists.")
                continue
            }
            val dev = device()
            if (dev.fingerprint != rec.reviewedFingerprint || dev.sdkInt != rec.reviewedSdk) {
                out += finish(ReplayStatus.SUSPENDED_CONTEXT_CHANGED, "The firmware or Android version changed since you enabled this. Review it, then enable it again.")
                continue
            }
            if (profile.contentHash != rec.reviewedProfileHash) {
                out += finish(ReplayStatus.SUSPENDED_PROFILE_CHANGED, "The profile was edited since you enabled this. Review it, then enable it again.")
                continue
            }
            val sameBoot = rec.lastBootId == bootId
            if (sameBoot && rec.attemptsThisBoot >= maxAttemptsPerBoot) {
                out += ReplayRun(rec.profileId, rec.status, "Already tried $maxAttemptsPerBoot times this boot; not trying again.")
                continue
            }
            // Count the attempt before doing anything, so a crash during the apply still counts toward the bound.
            rec = rec.copy(lastBootId = bootId, attemptsThisBoot = if (sameBoot) rec.attemptsThisBoot + 1 else 1, lastAttemptAt = clock())
            try {
                store.save(rec)
            } catch (e: IOException) {
                out += ReplayRun(rec.profileId, rec.status, "Could not save state, not applying.")
                continue
            }

            val pp = profileService.plan(profile, RequestSource.BOOT_REPLAY, rec.includeOptional)
            if (pp.unsupported.isNotEmpty()) {
                out += finish(ReplayStatus.SUSPENDED_CONTEXT_CHANGED, "${pp.unsupported.size} entr${if (pp.unsupported.size == 1) "y" else "ies"} no longer apply on this phone. Review the profile.")
                continue
            }
            if (pp.plan.blockers.any { it.code == IssueCode.NO_BACKEND }) {
                out += finish(ReplayStatus.PENDING_BACKEND_UNAVAILABLE, "Waiting for access: " + pp.plan.blockers.first { it.code == IssueCode.NO_BACKEND }.message)
                continue
            }
            if (pp.plan.blockers.isNotEmpty()) {
                out += finish(ReplayStatus.FAILED, pp.plan.blockers.first().message, failure = true)
                continue
            }
            if (!pp.plan.executable) {
                out += finish(ReplayStatus.UP_TO_DATE, "Everything already had the profile's values.", resetFailures = true)
                continue
            }
            when (val r = engine.apply(pp.plan.request, Confirmation.ReplayOptIn(profile.id))) {
                is ApplyResult.Finished ->
                    out += if (r.operation.status == OperationStatus.COMPLETED) {
                        finish(ReplayStatus.APPLIED, "Applied ${r.operation.entries.size} setting(s).", resetFailures = true)
                    } else {
                        finish(ReplayStatus.FAILED, "Apply ended as ${r.operation.status}; see history.", failure = true)
                    }
                ApplyResult.Busy -> out += finish(ReplayStatus.FAILED, "Another settings operation was running.", failure = true)
                is ApplyResult.NothingToDo -> out += finish(ReplayStatus.UP_TO_DATE, "Nothing to change.", resetFailures = true)
                is ApplyResult.Rejected, is ApplyResult.PlanChanged, is ApplyResult.Declined ->
                    out += finish(ReplayStatus.FAILED, "The engine refused the reapply.", failure = true)
            }
        }
        return out
    }
}
