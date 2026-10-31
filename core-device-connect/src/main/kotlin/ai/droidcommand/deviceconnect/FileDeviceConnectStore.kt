package ai.droidcommand.deviceconnect

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission

/** Owner-only JSON file used by the PC companion. The Android app uses the secrets vault. */
class FileDeviceConnectStore(private val path: Path) : DeviceConnectStore {
    @Synchronized
    override fun load(): DeviceConnectSnapshot {
        if (!Files.exists(path)) return DeviceConnectSnapshot()
        val raw = Files.readString(path)
        return DeviceConnectCodec.decode(raw) ?: DeviceConnectSnapshot()
    }

    @Synchronized
    override fun save(snapshot: DeviceConnectSnapshot) {
        Files.createDirectories(path.parent)
        val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
        Files.writeString(tmp, DeviceConnectCodec.encode(snapshot))
        runCatching {
            Files.setPosixFilePermissions(
                tmp,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        }
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
