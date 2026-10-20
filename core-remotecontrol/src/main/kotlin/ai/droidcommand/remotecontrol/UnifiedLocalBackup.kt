package ai.droidcommand.remotecontrol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * One local JSON snapshot of Remote Control mappings / targets / macros and
 * the last status-tray rows. **Does not** include API keys, VictorSMS
 * whitelist, code word, or any secret. No cloud upload.
 */
data class UnifiedLocalBackup(
    val version: Int = VERSION,
    val exportedAtMs: Long,
    val config: RemoteControlConfig,
    val statusTray: List<NodeStatusSnapshot> = emptyList(),
) {
    companion object {
        const val VERSION = 1
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
        if (dto.version != UnifiedLocalBackup.VERSION) {
            throw IllegalArgumentException("unsupported backup version ${dto.version}")
        }
        return dto.toModel()
    }
}

@Serializable
internal data class UnifiedLocalBackupDto(
    val version: Int = UnifiedLocalBackup.VERSION,
    val exportedAtMs: Long,
    val config: RemoteControlConfigDto = RemoteControlConfigDto(),
    val statusTray: List<NodeStatusSnapshotDto> = emptyList(),
) {
    fun toModel(): UnifiedLocalBackup = UnifiedLocalBackup(
        version = version,
        exportedAtMs = exportedAtMs,
        config = config.toModel(),
        statusTray = statusTray.map { it.toModel() },
    )

    companion object {
        fun from(backup: UnifiedLocalBackup) = UnifiedLocalBackupDto(
            version = backup.version,
            exportedAtMs = backup.exportedAtMs,
            config = RemoteControlConfigDto.from(backup.config),
            statusTray = backup.statusTray.map { NodeStatusSnapshotDto.from(it) },
        )
    }
}

@Serializable
internal data class NodeStatusSnapshotDto(
    val sourceNodeRef: String,
    val actionRef: String,
    val payload: Map<String, String> = emptyMap(),
    val receivedAtMs: Long,
) {
    fun toModel(): NodeStatusSnapshot = NodeStatusSnapshot(
        sourceNodeRef = sourceNodeRef,
        actionRef = actionRef,
        payload = payload,
        receivedAtMs = receivedAtMs,
    )

    companion object {
        fun from(snapshot: NodeStatusSnapshot) = NodeStatusSnapshotDto(
            sourceNodeRef = snapshot.sourceNodeRef,
            actionRef = snapshot.actionRef,
            payload = snapshot.payload,
            receivedAtMs = snapshot.receivedAtMs,
        )
    }
}
