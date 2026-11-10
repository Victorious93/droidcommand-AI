package ai.droidcommand.app.status

import ai.droidcommand.remotecontrol.InMemoryRemoteStatusTray
import ai.droidcommand.remotecontrol.NodeStatusSnapshot
import ai.droidcommand.remotecontrol.NodeStatusSnapshotCodec
import ai.droidcommand.remotecontrol.RemoteStatusTray
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Keeps the status tray across app restarts by writing the latest snapshots to a small JSON file.
 * Publish still drops expired rows; a corrupt file fails closed to an empty tray.
 */
class PersistingRemoteStatusTray(
    private val file: Path,
    private val memory: InMemoryRemoteStatusTray = InMemoryRemoteStatusTray(),
) : RemoteStatusTray {
    init {
        loadIntoMemory()
    }

    override fun consume(snapshot: NodeStatusSnapshot) {
        memory.consume(snapshot)
        persist(memory.publish(null))
    }

    override fun publish(nowMs: Long?): List<NodeStatusSnapshot> {
        val live = memory.publish(nowMs)
        if (nowMs != null) persist(live)
        return live
    }

    private fun loadIntoMemory() {
        if (!Files.isRegularFile(file)) return
        val text = runCatching { file.readText() }.getOrNull() ?: return
        val snaps = runCatching { NodeStatusSnapshotCodec.decodeList(text) }.getOrNull() ?: return
        snaps.forEach { memory.consume(it) }
    }

    private fun persist(snapshots: List<NodeStatusSnapshot>) {
        runCatching {
            val parent = file.parent
            if (parent != null) Files.createDirectories(parent)
            val tmp = file.resolveSibling("${file.fileName}.tmp")
            tmp.writeText(NodeStatusSnapshotCodec.encodeList(snapshots))
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}
