package ai.droidcommand.systemsettings

/** The three Android settings tables. [deviceWide] tables are not per-user: Global belongs to the device. */
enum class SettingsTable(val wire: String, val deviceWide: Boolean) {
    SYSTEM("system", false),
    SECURE("secure", false),
    GLOBAL("global", true),
    ;

    companion object {
        fun fromWire(text: String): SettingsTable? = entries.firstOrNull { it.wire == text }
    }
}

/**
 * One settings entry. Global is device-wide, so its [userId] is always [DEVICE_USER]; System and Secure belong to [userId].
 * Constructing a key with a bad name throws; use [parse] at a trust boundary.
 */
data class SettingKey(val table: SettingsTable, val name: String, val userId: Int) {
    init {
        require(NAME_PATTERN.matches(name)) { "Setting names use letters, digits, _ . : - only (max 128)." }
        require(!table.deviceWide || userId == DEVICE_USER) { "Global is device-wide; use SettingKey.of()." }
        require(userId >= 0) { "userId must not be negative" }
    }

    /** Stable text id used by the journal and baselines. */
    val id: String get() = "${table.wire}:$userId:$name"

    /** Plain-language scope shown beside every entry. */
    val scopeLabel: String get() = if (table.deviceWide) "whole device" else "user $userId"

    companion object {
        const val DEVICE_USER = 0
        val NAME_PATTERN = Regex("^[A-Za-z0-9_.:-]{1,128}$")

        fun of(table: SettingsTable, name: String, userId: Int): SettingKey =
            SettingKey(table, name, if (table.deviceWide) DEVICE_USER else userId)

        /** Null when the name is not acceptable. */
        fun parse(table: SettingsTable, name: String, userId: Int): SettingKey? =
            if (NAME_PATTERN.matches(name) && userId >= 0) of(table, name, userId) else null

        fun fromId(id: String): SettingKey? {
            val parts = id.split(':', limit = 3)
            if (parts.size != 3) return null
            val table = SettingsTable.fromWire(parts[0]) ?: return null
            val user = parts[1].toIntOrNull() ?: return null
            return parse(table, parts[2], user)
        }
    }
}

/**
 * The state of one entry. [Absent] (no row) and `Present("")` (a row holding the empty string) are different states and stay
 * different everywhere, including when restoring.
 */
sealed interface Snapshot {
    data object Absent : Snapshot
    data class Present(val value: String) : Snapshot
}

fun Snapshot.describe(): String = when (this) {
    Snapshot.Absent -> "(not set)"
    is Snapshot.Present -> if (value.isEmpty()) "(empty string)" else value
}

/** What a proposed change does: store [Put.value], or remove the row. */
sealed interface Proposed {
    data class Put(val value: String) : Proposed
    data object Delete : Proposed
}

fun Proposed.describe(): String = when (this) {
    is Proposed.Put -> if (value.isEmpty()) "(empty string)" else value
    Proposed.Delete -> "(remove the entry)"
}

fun Proposed.asSnapshot(): Snapshot = when (this) {
    is Proposed.Put -> Snapshot.Present(value)
    Proposed.Delete -> Snapshot.Absent
}

fun Snapshot.asProposed(): Proposed = when (this) {
    Snapshot.Absent -> Proposed.Delete
    is Snapshot.Present -> Proposed.Put(value)
}

/** Why a backend call did not work. Every kind is shown to the owner differently. */
enum class FailureKind {
    /** The app or backend lacks the Android permission (or the provider refused with a SecurityException). */
    PERMISSION_DENIED,

    /** The backend is not running, was revoked, the service died, or the device disconnected. */
    UNAVAILABLE,

    /** The backend did not answer in time. The write may or may not have happened. */
    TIMEOUT,

    /** Android or the OEM refused the value. */
    REJECTED,

    /** This backend cannot do that (for example another user's table, or listing a table). */
    UNSUPPORTED,

    /** The value cannot be read by this backend. Not the same as absent. */
    UNREADABLE,
    ERROR,
}

data class BackendFailure(val kind: FailureKind, val message: String)

sealed interface ReadResult {
    data class Value(val snapshot: Snapshot) : ReadResult
    data class Failed(val failure: BackendFailure) : ReadResult
}

sealed interface ListResult {
    /** [partial] is true when some lines could not be parsed (for example a value that spans lines). */
    data class Entries(val values: Map<String, String>, val partial: Boolean = false) : ListResult
    data class Failed(val failure: BackendFailure) : ListResult
}

sealed interface WriteResult {
    data object Done : WriteResult
    data class Failed(val failure: BackendFailure) : WriteResult
}

/** Facts about this phone that decide what can be offered. Built by :app from Build and DisplayManager. */
data class DeviceContext(
    val sdkInt: Int,
    val release: String,
    val manufacturer: String,
    val model: String,
    val fingerprint: String,
    /** The user this app runs as. */
    val userId: Int,
    /** From the display's supported modes. Empty when unknown. */
    val supportedRefreshRatesHz: List<Float> = emptyList(),
) {
    val summary: String get() = "$manufacturer $model, Android $release (API $sdkInt)"
}
