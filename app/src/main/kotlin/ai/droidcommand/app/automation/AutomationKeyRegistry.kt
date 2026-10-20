package ai.droidcommand.app.automation

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The saved automation keys, one [AutomationKeyCodec] line each, in [FILE_NAME] under [directory]. A save is written to a
 * temporary file and then moved into place, so a crash mid-write cannot leave a half-written list. Lines that do not
 * decode are skipped rather than failing the whole list.
 */
class AutomationKeyRegistry(private val directory: File) {
    fun load(): List<AutomationKey> {
        val file = File(directory, FILE_NAME)
        if (!file.isFile) return emptyList()
        return file.readLines().mapNotNull { AutomationKeyCodec.decode(it) }
    }

    fun save(keys: List<AutomationKey>) {
        directory.mkdirs()
        val target = File(directory, FILE_NAME)
        val temporary = File(directory, "$FILE_NAME.tmp")
        temporary.writeText(keys.joinToString(separator = "") { AutomationKeyCodec.encode(it) + "\n" })
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        const val FILE_NAME = "automation-keys.txt"
    }
}
