package ai.droidcommand.remotecontrol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Versioned local JSON snapshot of the controller's own macros, mappings,
 * and device pairings (plus targets / last status-tray rows).
 *
 * Exact unified fleet backup schema is
 * **[CONTRACT TBD pending Fleet Orchestrator]**. This file is the
 * controller-local encoding, not that shared contract.
 *
 * **Does not** include API keys, VictorSMS whitelist, code word, or any
 * secret. No cloud upload.
 */
data class UnifiedLocalBackup(
    val version: Int = VERSION,
    val exportedAtMs: Long,
    val config: RemoteControlConfig,
    val statusTray: List<NodeStatusSnapshot> = emptyList(),
    val schemaNote: String = SCHEMA_NOTE,
) {
    companion object {
        const val VERSION = 2
        const val SCHEMA_NOTE =
            "[CONTRACT TBD pending Fleet Orchestrator] unified fleet backup schema; " +
                "this file is the controller-local macros+mappings+pairings encoding"
    }
}

class UnifiedLocalBackupStore(
    private val file: Path,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }
    private val lock = Any()

    fun export(config: RemoteControlConfig, tray: List<NodeStatusSnapshot> = emptyList()): UnifiedLocalBackup {
        val backup = UnifiedLocalBackup(
            exportedAtMs = nowMs(),
            config = config,
            statusTray = tray,
        )
        val encoded = json.encodeToString(UnifiedLocalBackupDto.serializer(), UnifiedLocalBackupDto.from(backup))
        synchronized(lock) {
            file.parent?.let { Files.createDirectories(it) }
            val temporary = Files.createTempFile(file.parent, ".remote-backup-", ".tmp")
            try {
                Files.write(temporary, encoded.toByteArray(Charsets.UTF_8))
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(temporary)
            }
        }
        return backup
    }

    fun import(): UnifiedLocalBackup {
        if (!Files.exists(file) || Files.size(file) == 0L) {
            throw IllegalArgumentException("no local backup at $file")
        }
        val raw = String(Files.readAllBytes(file), Charsets.UTF_8)
        val dto = json.decodeFromString(UnifiedLocalBackupDto.serializer(), raw)
        if (dto.version !in SUPPORTED_VERSIONS) {
            throw IllegalArgumentException("unsupported backup version ${dto.version}")
        }
        return dto.toModel()
    }

    companion object {
        private val SUPPORTED_VERSIONS = setOf(1, UnifiedLocalBackup.VERSION)
    }
}

@Serializable
internal data class UnifiedLocalBackupDto(
    val version: Int = UnifiedLocalBackup.VERSION,
    val exportedAtMs: Long,
    val schemaNote: String = UnifiedLocalBackup.SCHEMA_NOTE,
    val config: RemoteControlConfigDto = RemoteControlConfigDto(),
    val statusTray: List<NodeStatusSnapshotDto> = emptyList(),
) {
    fun toModel(): UnifiedLocalBackup = UnifiedLocalBackup(
        version = version,
        exportedAtMs = exportedAtMs,
        config = config.toModel(),
        statusTray = statusTray.map { it.toModel() },
        schemaNote = schemaNote,
    )

    companion object {
        fun from(backup: UnifiedLocalBackup) = UnifiedLocalBackupDto(
            version = backup.version,
            exportedAtMs = backup.exportedAtMs,
            schemaNote = backup.schemaNote,
            config = RemoteControlConfigDto.from(backup.config),
            statusTray = backup.statusTray.map { NodeStatusSnapshotDto.from(it) },
        )
    }
}
