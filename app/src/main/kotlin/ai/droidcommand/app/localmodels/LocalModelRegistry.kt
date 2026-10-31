package ai.droidcommand.app.localmodels

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The imported models, persisted as a text file in [dir]. `ModelRepository` keeps its own registry in memory only, so
 * this file is what survives an app restart. A record is loaded only while its model file is still present.
 */
class LocalModelRegistry(private val dir: File) {
    private val file = File(dir, FILE_NAME)

    fun load(): List<ImportedModel> {
        if (!file.isFile) return emptyList()
        return file.readLines(Charsets.UTF_8)
            .mapNotNull(ImportedModelCodec::decode)
            .filter(::filePresent)
    }

    /** A copied model lives under [dir]; a scanned one is the file at [ImportedModel.externalPath]. */
    private fun filePresent(model: ImportedModel): Boolean {
        val external = model.externalPath?.let(::File)
        return if (external != null) external.isFile else File(dir, model.metadata.fileName).isFile
    }

    /** Writes through a temporary file, so an interrupted save leaves the previous registry in place. */
    fun save(models: List<ImportedModel>) {
        dir.mkdirs()
        val temp = File(dir, "$FILE_NAME.tmp")
        temp.writeText(models.joinToString(separator = "") { ImportedModelCodec.encode(it) + "\n" }, Charsets.UTF_8)
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        const val FILE_NAME = "registry.txt"
    }
}
