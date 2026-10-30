package ai.droidcommand.llm.local

import java.io.File

/** A `.gguf` file found on local storage. [path] is absolute. */
data class ScannedGguf(
    val path: String,
    val fileName: String,
    val sizeBytes: Long,
    val quant: String?,
)

/**
 * Walks caller-supplied roots one level (or one extra `models/` child) for `*.gguf` files.
 * Pure `java.io.File`: Android scoped-storage policy is the caller's problem (pass only
 * directories the process can actually read). Unreadable or missing roots are skipped, not thrown.
 */
object GgufStorageScanner {
    const val MAX_FILES = 64
    private const val MAX_DEPTH = 2

    fun scan(roots: List<File>, maxFiles: Int = MAX_FILES): List<ScannedGguf> {
        val found = LinkedHashMap<String, ScannedGguf>()
        for (root in roots) {
            walk(root, depth = 0, found, maxFiles)
            if (found.size >= maxFiles) break
        }
        return found.values.toList()
    }

    /**
     * Conventional places owners drop GGUF files. [externalStorage] is typically
     * `/storage/emulated/0` (also `/sdcard`). Null skips those roots rather than guessing.
     */
    fun commonRoots(externalStorage: File?): List<File> {
        if (externalStorage == null) return emptyList()
        return listOf(
            File(externalStorage, "Download"),
            File(externalStorage, "Download/models"),
            File(externalStorage, "Documents/models"),
            File(externalStorage, "models"),
        )
    }

    private fun walk(dir: File, depth: Int, found: MutableMap<String, ScannedGguf>, maxFiles: Int) {
        if (found.size >= maxFiles) return
        if (depth > MAX_DEPTH) return
        if (!dir.isDirectory) return
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (found.size >= maxFiles) return
            if (child.isFile && GgufFileName.looksLikeGguf(child.name)) {
                val path = try {
                    child.canonicalPath
                } catch (_: Exception) {
                    child.absolutePath
                }
                found.putIfAbsent(
                    path,
                    ScannedGguf(
                        path = path,
                        fileName = child.name,
                        sizeBytes = child.length().coerceAtLeast(0L),
                        quant = GgufQuant.fromFileName(child.name),
                    ),
                )
            } else if (child.isDirectory && depth < MAX_DEPTH) {
                // Extra `models/` folders are the interesting children; ignore hidden/dot dirs.
                if (child.name.startsWith(".")) continue
                walk(child, depth + 1, found, maxFiles)
            }
        }
    }
}
