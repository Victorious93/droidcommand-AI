package ai.droidcommand.repomap

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** A parsed file. [path] is relative to the indexed root, `/`-separated. */
class IndexedFile(val path: String, val parsed: ParsedFile)

/** [skipped] counts recognized-language files that were not indexed (too large, not UTF-8, unreadable); [truncated] is true if [RepoIndexer.maxFiles] cut the list. */
class RepoIndex(val files: List<IndexedFile>, val skipped: Int, val truncated: Boolean)

/**
 * Walks a source tree and parses every file a [SourceParser] supports.
 *
 * Safety: the root must lie inside [authorizedRoots] once symlinks are resolved (an empty list authorizes
 * nothing); symlinks are never followed, so a link inside the tree cannot expose files outside it; build and
 * dependency directories are pruned instead of walked; files over [maxFileBytes] or that are not valid UTF-8
 * text are skipped; at most [maxFiles] files are indexed (in path order, so the cut is deterministic).
 *
 * Repeat calls re-parse only files whose size or modification time changed. [parsedFileCount] counts actual
 * parses since construction, which is how that is observable.
 */
class RepoIndexer(
    private val authorizedRoots: List<Path>,
    private val parsers: List<SourceParser> = listOf(LexicalSourceParser()),
    val maxFiles: Int = 5_000,
    private val maxFileBytes: Long = 1_000_000,
) {
    private class Cached(val size: Long, val modifiedMillis: Long, val parsed: ParsedFile?)

    private val cache = ConcurrentHashMap<Path, Cached>()
    private val parsed = AtomicLong()

    val parsedFileCount: Long get() = parsed.get()

    /** @throws IllegalArgumentException if [root] is not inside an authorized root. */
    fun index(root: Path): RepoIndex {
        val realRoot = try {
            root.toRealPath()
        } catch (e: IOException) {
            throw IllegalArgumentException("'$root' does not exist or cannot be read")
        }
        val allowed = authorizedRoots.mapNotNull { runCatching { it.toRealPath() }.getOrNull() }
        require(allowed.any { realRoot.startsWith(it) }) { "'$root' is outside every authorized root" }
        require(Files.isDirectory(realRoot)) { "'$root' is not a directory" }

        val found = ArrayList<Path>()
        Files.walkFileTree(
            realRoot,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                    if (dir != realRoot && dir.fileName.toString() in SKIPPED_DIRECTORIES) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile && parsers.any { it.supports(file) }) found.add(file)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            },
        )
        found.sortBy { realRoot.relativize(it).toString() }
        val truncated = found.size > maxFiles
        val selected = if (truncated) found.subList(0, maxFiles) else found

        var skipped = 0
        val files = ArrayList<IndexedFile>(selected.size)
        val keep = HashSet<Path>()
        for (file in selected) {
            keep.add(file)
            val result = load(file)
            if (result == null) skipped++ else files.add(IndexedFile(realRoot.relativize(file).joinToString("/") { it.toString() }, result))
        }
        cache.keys.retainAll(keep) // forget files that vanished or fell outside the cap
        return RepoIndex(files, skipped, truncated)
    }

    private fun load(file: Path): ParsedFile? {
        val attrs = try {
            Files.readAttributes(file, BasicFileAttributes::class.java)
        } catch (_: IOException) {
            return null
        }
        val modified = attrs.lastModifiedTime().toMillis()
        cache[file]?.let { if (it.size == attrs.size() && it.modifiedMillis == modified) return it.parsed }

        val result = parseFile(file, attrs.size())
        cache[file] = Cached(attrs.size(), modified, result)
        return result
    }

    private fun parseFile(file: Path, size: Long): ParsedFile? {
        if (size > maxFileBytes) return null
        val text = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(Files.readAllBytes(file))).toString()
        } catch (_: CharacterCodingException) {
            return null
        } catch (_: IOException) {
            return null
        }
        if (text.indexOf('\u0000') >= 0) return null
        val parser = parsers.firstOrNull { it.supports(file) } ?: return null
        parsed.incrementAndGet()
        return parser.parse(file, text)
    }

    private companion object {
        val SKIPPED_DIRECTORIES = setOf(
            ".git", ".hg", ".svn", "build", ".gradle", "node_modules", ".idea", ".vscode", "out", "target", "dist",
            "__pycache__", ".venv", "venv", ".tox", ".mypy_cache", ".pytest_cache", "bower_components",
        )
    }
}
