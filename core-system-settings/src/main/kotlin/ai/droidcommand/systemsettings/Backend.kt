package ai.droidcommand.systemsettings

import ai.droidcommand.shizuku.PrivilegedResult
import ai.droidcommand.shizuku.toolbox.ToolboxBackend
import ai.droidcommand.shizuku.toolbox.ToolboxCommand

/** Least to most privileged. Declaration order is the selection order: the engine never picks a higher tier when a lower one can do the job. */
enum class AccessKind(val label: String) {
    STANDARD("Standard permissions"),
    SHIZUKU("Shizuku"),
    ROOT("Root"),
}

enum class BackendState {
    READY,

    /** Reachable, but a needed Android permission is missing, so writes to some tables will fail. */
    PERMISSION_MISSING,

    /** Not running, revoked, disconnected, or not present. */
    UNAVAILABLE,
}

/**
 * What a backend can do right now, from a probe that changes nothing. [writableTables] is empty for a read-only backend.
 * [detail] is shown to the owner as is, so it never contains setting values.
 */
data class BackendStatus(
    val kind: AccessKind,
    val state: BackendState,
    val detail: String,
    val readableTables: Set<SettingsTable>,
    val writableTables: Set<SettingsTable>,
    val canListTables: Set<SettingsTable>,
    /** True when this backend can address a user other than the one the app runs as. */
    val crossUser: Boolean,
) {
    val usable: Boolean get() = state != BackendState.UNAVAILABLE
    val readOnly: Boolean get() = usable && writableTables.isEmpty()
}

/**
 * One way of reading and writing the settings tables. Implementations never decide policy and never fall back to another backend;
 * the engine selects one, runs it, and reports what happened.
 */
interface SettingsBackend {
    val kind: AccessKind
    fun status(): BackendStatus
    fun read(key: SettingKey): ReadResult
    fun list(table: SettingsTable, userId: Int): ListResult
    fun write(key: SettingKey, value: String): WriteResult
    fun delete(key: SettingKey): WriteResult
}

/**
 * The Android SettingsProvider as seen through the platform API (ContentResolver, Settings.*). Implemented in :app. Methods may throw
 * [SecurityException] (missing permission) or [IllegalArgumentException]; [StandardSettingsBackend] maps those to typed failures.
 */
interface PlatformSettingsAccess {
    /** The user this process runs as. Standard access can only address this user. */
    val currentUserId: Int

    /** Null means the provider has no such row. */
    fun getString(table: SettingsTable, name: String): String?

    /** Every row of [table], or throws when the provider will not enumerate it. */
    fun listAll(table: SettingsTable): Map<String, String>

    /** The Settings API's own return value: false means the provider did not store the value. */
    fun putString(table: SettingsTable, name: String, value: String): Boolean

    /** Number of rows removed. */
    fun delete(table: SettingsTable, name: String): Int

    /** Whether this app currently holds the permission needed to write [table]. */
    fun canWrite(table: SettingsTable): Boolean

    /** A human sentence about what is missing, for the Access tab. */
    fun missingPermissionHint(table: SettingsTable): String
}

/** Least-privileged backend: ordinary Android APIs. Reads need no special access; writes need WRITE_SETTINGS (System) or WRITE_SECURE_SETTINGS (Secure/Global). */
class StandardSettingsBackend(private val platform: PlatformSettingsAccess) : SettingsBackend {
    override val kind = AccessKind.STANDARD

    override fun status(): BackendStatus {
        val writable = SettingsTable.entries.filter { platform.canWrite(it) }.toSet()
        val missing = SettingsTable.entries.filter { it !in writable }
        val detail = if (missing.isEmpty()) {
            "This app can read and write all three tables for its own user."
        } else {
            "Read-only for: " + missing.joinToString { it.wire } + ". " + missing.joinToString(" ") { platform.missingPermissionHint(it) }
        }
        return BackendStatus(
            kind = kind,
            state = if (missing.isEmpty()) BackendState.READY else BackendState.PERMISSION_MISSING,
            detail = detail,
            readableTables = SettingsTable.entries.toSet(),
            writableTables = writable,
            canListTables = SettingsTable.entries.toSet(),
            crossUser = false,
        )
    }

    override fun read(key: SettingKey): ReadResult {
        wrongUser(key)?.let { return ReadResult.Failed(it) }
        return try {
            val value = platform.getString(key.table, key.name)
            ReadResult.Value(if (value == null) Snapshot.Absent else Snapshot.Present(value))
        } catch (e: SecurityException) {
            ReadResult.Failed(BackendFailure(FailureKind.UNREADABLE, "Android does not let this app read ${key.table.wire}/${key.name}."))
        } catch (e: RuntimeException) {
            ReadResult.Failed(BackendFailure(FailureKind.ERROR, "Reading failed: ${e.javaClass.simpleName}"))
        }
    }

    override fun list(table: SettingsTable, userId: Int): ListResult {
        if (userId != platform.currentUserId && !table.deviceWide) {
            return ListResult.Failed(BackendFailure(FailureKind.UNSUPPORTED, "Standard access only reaches the user this app runs as."))
        }
        return try {
            ListResult.Entries(platform.listAll(table))
        } catch (e: SecurityException) {
            ListResult.Failed(BackendFailure(FailureKind.PERMISSION_DENIED, "Android does not let this app list the ${table.wire} table."))
        } catch (e: RuntimeException) {
            ListResult.Failed(BackendFailure(FailureKind.UNSUPPORTED, "This device will not list the ${table.wire} table to an app."))
        }
    }

    override fun write(key: SettingKey, value: String): WriteResult {
        wrongUser(key)?.let { return WriteResult.Failed(it) }
        if (!platform.canWrite(key.table)) {
            return WriteResult.Failed(BackendFailure(FailureKind.PERMISSION_DENIED, platform.missingPermissionHint(key.table)))
        }
        return try {
            if (platform.putString(key.table, key.name, value)) {
                WriteResult.Done
            } else {
                WriteResult.Failed(BackendFailure(FailureKind.REJECTED, "Android did not store the value."))
            }
        } catch (e: SecurityException) {
            WriteResult.Failed(BackendFailure(FailureKind.PERMISSION_DENIED, platform.missingPermissionHint(key.table)))
        } catch (e: RuntimeException) {
            WriteResult.Failed(BackendFailure(FailureKind.ERROR, "Writing failed: ${e.javaClass.simpleName}"))
        }
    }

    override fun delete(key: SettingKey): WriteResult {
        wrongUser(key)?.let { return WriteResult.Failed(it) }
        if (!platform.canWrite(key.table)) {
            return WriteResult.Failed(BackendFailure(FailureKind.PERMISSION_DENIED, platform.missingPermissionHint(key.table)))
        }
        return try {
            platform.delete(key.table, key.name)
            WriteResult.Done
        } catch (e: SecurityException) {
            WriteResult.Failed(BackendFailure(FailureKind.PERMISSION_DENIED, platform.missingPermissionHint(key.table)))
        } catch (e: RuntimeException) {
            WriteResult.Failed(BackendFailure(FailureKind.ERROR, "Removing failed: ${e.javaClass.simpleName}"))
        }
    }

    private fun wrongUser(key: SettingKey): BackendFailure? =
        if (!key.table.deviceWide && key.userId != platform.currentUserId) {
            BackendFailure(FailureKind.UNSUPPORTED, "Standard access only reaches the user this app runs as (user ${platform.currentUserId}).")
        } else {
            null
        }
}

/** The exact argument lists the shell backends send. Typed arguments only: nothing is joined into a command string here. */
object SettingsCommands {
    private const val TIMEOUT_MS = 10_000L

    fun get(key: SettingKey) = cmd("Read setting", listOf("settings", "--user", key.userId.toString(), "get", key.table.wire, key.name))

    fun list(table: SettingsTable, userId: Int) =
        cmd("List settings", listOf("settings", "--user", userId.toString(), "list", table.wire))

    fun put(key: SettingKey, value: String) =
        cmd("Change setting", listOf("settings", "--user", key.userId.toString(), "put", key.table.wire, key.name, value), destructive = true)

    fun delete(key: SettingKey) =
        cmd("Remove setting", listOf("settings", "--user", key.userId.toString(), "delete", key.table.wire, key.name), destructive = true)

    private fun cmd(label: String, argv: List<String>, destructive: Boolean = false) =
        ToolboxCommand(label, argv, destructive = destructive, timeoutMs = TIMEOUT_MS)

    /** Values handed to `settings put` must be one line and free of NUL. Everything else is quoted or passed as argv by the transport. */
    fun valueProblem(value: String): String? = when {
        value.length > MAX_VALUE_LENGTH -> "That value is too long (max $MAX_VALUE_LENGTH characters)."
        value.any { it == '\n' || it == '\r' || it == '\u0000' } -> "Values must be one line without control characters."
        else -> null
    }

    const val MAX_VALUE_LENGTH = 4096
}

/**
 * Shizuku or root, through the existing System Toolbox transports ([ToolboxBackend]): Shizuku runs the argv directly, root goes through
 * `su -c` with every argument shell-quoted by the toolbox. The `settings` shell command is the documented command-line front end to the
 * SettingsProvider, so the settings database files are never touched.
 *
 * Nothing here escalates: a ROOT backend is only ever used when the engine selected it.
 */
class ShellSettingsBackend(
    override val kind: AccessKind,
    private val transport: ToolboxBackend,
    private val detail: () -> String = { "" },
) : SettingsBackend {
    init {
        require(kind == AccessKind.SHIZUKU || kind == AccessKind.ROOT) { "Shell backends are Shizuku or root" }
    }

    override fun status(): BackendStatus {
        val available = transport.isAvailable()
        val all = SettingsTable.entries.toSet()
        val extra = detail().ifBlank { "" }
        return BackendStatus(
            kind = kind,
            state = if (available) BackendState.READY else BackendState.UNAVAILABLE,
            detail = if (available) {
                ("${kind.label} is ready." + if (extra.isNotEmpty()) " $extra" else "")
            } else {
                extra.ifBlank { "${kind.label} is not available right now." }
            },
            readableTables = if (available) all else emptySet(),
            writableTables = if (available) all else emptySet(),
            canListTables = if (available) all else emptySet(),
            crossUser = available,
        )
    }

    override fun read(key: SettingKey): ReadResult {
        val out = run(SettingsCommands.get(key)).let {
            when (it) {
                is Outcome.Bad -> return ReadResult.Failed(it.failure)
                is Outcome.Ok -> it.stdout
            }
        }
        val text = out.removeSuffix("\n")
        if (text != "null") return ReadResult.Value(Snapshot.Present(text))
        // `settings get` prints "null" both for a missing row and for a row that holds the text "null"; the table listing tells them apart.
        return when (val listing = list(key.table, key.userId)) {
            is ListResult.Failed -> ReadResult.Failed(
                BackendFailure(FailureKind.UNREADABLE, "The value reads as \"null\" and the table could not be listed to tell absent from the text null."),
            )
            is ListResult.Entries -> ReadResult.Value(
                if (listing.values.containsKey(key.name)) Snapshot.Present("null") else Snapshot.Absent,
            )
        }
    }

    override fun list(table: SettingsTable, userId: Int): ListResult {
        val out = when (val r = run(SettingsCommands.list(table, userId))) {
            is Outcome.Bad -> return ListResult.Failed(r.failure)
            is Outcome.Ok -> r.stdout
        }
        val values = LinkedHashMap<String, String>()
        var partial = false
        for (line in out.lineSequence()) {
            if (line.isEmpty()) continue
            val eq = line.indexOf('=')
            if (eq <= 0) {
                partial = true
                continue
            }
            values[line.substring(0, eq)] = line.substring(eq + 1)
        }
        return ListResult.Entries(values, partial)
    }

    override fun write(key: SettingKey, value: String): WriteResult {
        SettingsCommands.valueProblem(value)?.let { return WriteResult.Failed(BackendFailure(FailureKind.REJECTED, it)) }
        return when (val r = run(SettingsCommands.put(key, value))) {
            is Outcome.Bad -> WriteResult.Failed(r.failure)
            // `settings put` can exit 0 without storing; the engine's readback is what proves a write.
            is Outcome.Ok -> WriteResult.Done
        }
    }

    override fun delete(key: SettingKey): WriteResult = when (val r = run(SettingsCommands.delete(key))) {
        is Outcome.Bad -> WriteResult.Failed(r.failure)
        is Outcome.Ok -> WriteResult.Done
    }

    private sealed interface Outcome {
        data class Ok(val stdout: String) : Outcome
        data class Bad(val failure: BackendFailure) : Outcome
    }

    private fun run(command: ToolboxCommand): Outcome {
        if (!transport.isAvailable()) {
            return Outcome.Bad(BackendFailure(FailureKind.UNAVAILABLE, "${kind.label} is not available right now."))
        }
        return when (val r = transport.run(command)) {
            is PrivilegedResult.Failure -> Outcome.Bad(
                // A failure object (as opposed to a non-zero exit) means the service itself could not run the command.
                BackendFailure(
                    if (transport.isAvailable()) FailureKind.ERROR else FailureKind.UNAVAILABLE,
                    r.reason.take(MAX_MESSAGE),
                ),
            )
            is PrivilegedResult.Success -> when {
                r.timedOut -> Outcome.Bad(BackendFailure(FailureKind.TIMEOUT, "${kind.label} did not answer in time."))
                r.exitCode != 0 -> Outcome.Bad(classify(r.stderr.ifBlank { r.stdout }))
                else -> Outcome.Ok(r.stdout)
            }
        }
    }

    private fun classify(message: String): BackendFailure {
        val m = message.trim().take(MAX_MESSAGE)
        val lower = m.lowercase()
        val kind = when {
            "securityexception" in lower || "permission denial" in lower || "requires android.permission" in lower ||
                "not allowed to" in lower -> FailureKind.PERMISSION_DENIED
            "can't find service" in lower || "could not find service" in lower || "deadobject" in lower ||
                "broken pipe" in lower -> FailureKind.UNAVAILABLE
            else -> FailureKind.ERROR
        }
        return BackendFailure(kind, m.ifBlank { "The command failed." })
    }

    private companion object {
        const val MAX_MESSAGE = 200
    }
}
