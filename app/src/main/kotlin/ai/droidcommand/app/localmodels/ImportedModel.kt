package ai.droidcommand.app.localmodels

import ai.droidcommand.llm.local.ModelMetadata
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * A GGUF file the owner imported. [metadata] is what the core `ModelRepository` checks the file against. Its digest
 * was taken from the file as it was imported, so it proves the file has not changed since, not where it came from.
 */
data class ImportedModel(val metadata: ModelMetadata, val importedAtMillis: Long) {
    val id: String get() = metadata.id
    val label: String get() = metadata.displayName
}

/** One registry line per model: seven fields, each percent-encoded, joined by tabs. Pure, so it is JVM-tested. */
object ImportedModelCodec {
    private const val SEPARATOR = "\t"
    private const val FIELD_COUNT = 7
    private val UTF8 = Charsets.UTF_8.name()

    fun encode(model: ImportedModel): String {
        val m = model.metadata
        return listOf(
            m.id,
            m.displayName,
            m.fileName,
            m.sha256,
            m.contextTokens.toString(),
            (m.sizeBytes ?: 0L).toString(),
            model.importedAtMillis.toString(),
        ).joinToString(SEPARATOR) { URLEncoder.encode(it, UTF8) }
    }

    /** Null for any line that is not a complete, valid record. */
    fun decode(line: String): ImportedModel? {
        val fields = line.split(SEPARATOR)
        if (fields.size != FIELD_COUNT) return null
        return try {
            val f = fields.map { URLDecoder.decode(it, UTF8) }
            val size = f[5].toLong()
            ImportedModel(
                metadata = ModelMetadata(
                    id = f[0],
                    displayName = f[1],
                    fileName = f[2],
                    sha256 = f[3],
                    contextTokens = f[4].toInt(),
                    sizeBytes = size.takeIf { it > 0L },
                ),
                importedAtMillis = f[6].toLong(),
            )
        } catch (_: RuntimeException) {
            // NumberFormatException, an invalid percent-escape, or ModelMetadata's own checks.
            null
        }
    }
}
