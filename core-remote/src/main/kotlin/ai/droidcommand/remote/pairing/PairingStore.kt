package ai.droidcommand.remote.pairing

import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.util.Base64

/** One saved pairing: the record and its secret. */
class StoredPairing(val device: PairedDevice, val secret: PairingSecret) {
    override fun toString(): String = "StoredPairing(device=$device, secret=<redacted>)"
}

/** Where a [PairingRegistry] keeps its pairings between runs. */
interface PairingStore {
    /** Every saved pairing, oldest first; empty when nothing has been saved yet. */
    fun load(): List<StoredPairing>

    /** Replaces everything saved with [pairings]. */
    fun save(pairings: List<StoredPairing>)
}

/**
 * Saves pairings, secrets included, to one file that only the owning user can read or write
 * (POSIX mode 0600), the way SSH and WireGuard keep their private keys. Anyone who can read the
 * file can pair as any controller in it, so:
 * - the file is always created with mode 0600 (never widened afterwards), and each save goes to
 *   a 0600 temporary file in the same directory that is then moved into place;
 * - [load] refuses a file that group or others can read or write, as `ssh` does with a private
 *   key, instead of trusting a secret someone else may have seen or planted.
 *
 * Needs a file system with POSIX permissions (Linux, macOS, Android/Termux). On any other the
 * constructor throws [UnsupportedOperationException], so a caller can fall back to memory only
 * rather than write secrets into a file it can't protect. This is for the JVM `cli`; on the
 * Android app, pairings belong in Android Keystore instead.
 *
 * Not safe for two processes writing the same file at once: the last save wins.
 */
class FilePairingStore(private val path: Path) : PairingStore {
    init {
        val probe = path.toAbsolutePath().parent ?: path.toAbsolutePath()
        val fileSystem = probe.fileSystem
        if ("posix" !in fileSystem.supportedFileAttributeViews()) {
            throw UnsupportedOperationException("$path is not on a file system with POSIX permissions")
        }
    }

    override fun load(): List<StoredPairing> {
        if (!Files.exists(path)) return emptyList()
        val permissions = Files.getPosixFilePermissions(path)
        val tooOpen = permissions - setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        if (tooOpen.isNotEmpty()) {
            throw IOException(
                "Refusing to read $path: its permissions are ${PosixFilePermissions.toString(permissions)}; " +
                    "only its owner may read or write it (chmod 600 $path)",
            )
        }
        val lines = Files.readAllLines(path).filter { it.isNotBlank() }
        if (lines.firstOrNull() != HEADER) throw IOException("$path is not a pairing file this version understands")
        return lines.drop(1).mapIndexed { index, line ->
            try {
                decode(line)
            } catch (e: IllegalArgumentException) {
                throw IOException("$path line ${index + 2} is malformed: ${e.message}", e)
            }
        }
    }

    override fun save(pairings: List<StoredPairing>) {
        val directory = path.toAbsolutePath().parent
        Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        val temp = Files.createTempFile(directory, ".pairings", ".tmp", OWNER_ONLY)
        try {
            Files.write(temp, (listOf(HEADER) + pairings.map(::encode)).map { "$it\n" }.joinToString("").toByteArray())
            try {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun encode(pairing: StoredPairing): String {
        val device = pairing.device
        return listOf(
            device.id,
            Base64.getUrlEncoder().withoutPadding().encodeToString(device.displayName.toByteArray()),
            device.pairedAt.toString(),
            device.expiresAt?.toString() ?: NONE,
            device.revokedAt?.toString() ?: NONE,
            pairing.secret.encode(),
        ).joinToString("\t")
    }

    private fun decode(line: String): StoredPairing {
        val fields = line.split("\t")
        require(fields.size == 6) { "expected 6 fields, got ${fields.size}" }
        fun instant(value: String): Instant? = if (value == NONE) null else parseInstant(value)
        val device = PairedDevice(
            id = fields[0].also { require(it.isNotBlank()) { "empty id" } },
            displayName = Base64.getUrlDecoder().decode(fields[1]).decodeToString(),
            pairedAt = parseInstant(fields[2]),
            expiresAt = instant(fields[3]),
            revokedAt = instant(fields[4]),
        )
        return StoredPairing(device, PairingSecret.decode(fields[5]))
    }

    private fun parseInstant(value: String): Instant = try {
        Instant.parse(value)
    } catch (e: java.time.format.DateTimeParseException) {
        throw IllegalArgumentException("bad time '$value'")
    }

    private companion object {
        const val HEADER = "droidcommand-pairings v1"
        const val NONE = "-"
        val OWNER_ONLY = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
    }
}
