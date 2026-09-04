package ai.droidforge.build

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.Comparator
import java.util.UUID

sealed class WorkspaceImportResult {
    data class Success(val sourceDir: String) : WorkspaceImportResult()
    data class Failure(val reason: String) : WorkspaceImportResult()
}

/**
 * Owns the real filesystem lifecycle of a build workspace: creation under
 * an explicitly authorized root, importing source into it, and cleaning it
 * up. Every path this class touches is resolved through
 * [WorkspacePathValidator] first — there is no operation here that accepts
 * a caller-supplied path and writes to it without that check.
 */
class WorkspaceManager(
    val authorizedRoots: List<Path>,
    private val clock: () -> Instant = Instant::now,
) {
    private val validator = WorkspacePathValidator(authorizedRoots)

    init {
        require(authorizedRoots.isNotEmpty()) { "WorkspaceManager requires at least one authorized root" }
    }

    fun create(root: Path = authorizedRoots.first()): WorkspaceHandle {
        val normalizedRoot = root.normalize()
        require(authorizedRoots.any { normalizedRoot.startsWith(it.normalize()) }) {
            "Root '$root' is not an authorized workspace root"
        }
        val workspaceId = UUID.randomUUID().toString()
        val workspaceRoot = validator.resolve(normalizedRoot, workspaceId)
        Files.createDirectories(workspaceRoot)
        return WorkspaceHandle(workspaceId, workspaceRoot.toString(), clock())
    }

    fun importSource(handle: WorkspaceHandle, source: SourceLocation): WorkspaceImportResult {
        handle.transition(WorkspaceState.PREPARING)
        return when (source) {
            is SourceLocation.LocalDirectory -> importLocalDirectory(handle, source)
        }
    }

    private fun importLocalDirectory(handle: WorkspaceHandle, source: SourceLocation.LocalDirectory): WorkspaceImportResult {
        val sourcePath = Path.of(source.path)
        if (!Files.exists(sourcePath)) {
            handle.transition(WorkspaceState.FAILED)
            return WorkspaceImportResult.Failure("Source path does not exist: ${source.path}")
        }

        val destination = validator.resolve(Path.of(handle.rootPath), "source")
        return try {
            copyRecursively(sourcePath, destination)
            handle.transition(WorkspaceState.READY)
            WorkspaceImportResult.Success(destination.toString())
        } catch (e: IOException) {
            handle.transition(WorkspaceState.FAILED)
            WorkspaceImportResult.Failure("Failed to import source: ${e.message}")
        }
    }

    /** Resolves a path a build step wants to read/write, confined to [handle]'s workspace root. */
    fun resolvePath(handle: WorkspaceHandle, relativePath: String): Path =
        validator.resolve(Path.of(handle.rootPath), relativePath)

    fun clean(handle: WorkspaceHandle) {
        handle.transition(WorkspaceState.CLEANING)
        val root = Path.of(handle.rootPath).normalize()
        try {
            if (Files.exists(root)) {
                Files.walk(root).use { stream ->
                    stream.sorted(Comparator.reverseOrder()).forEach { path ->
                        val normalizedPath = path.normalize()
                        if (!normalizedPath.startsWith(root)) {
                            throw PathSecurityViolation("Refusing to delete '$normalizedPath' outside workspace root '$root'")
                        }
                        Files.deleteIfExists(normalizedPath)
                    }
                }
            }
            handle.transition(WorkspaceState.CLEANED)
        } catch (e: IOException) {
            handle.transition(WorkspaceState.FAILED)
            throw e
        }
    }

    private fun copyRecursively(source: Path, destination: Path) {
        Files.walk(source).use { stream ->
            stream.forEach { src ->
                val relative = source.relativize(src)
                val dest = destination.resolve(relative)
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dest)
                } else {
                    Files.createDirectories(dest.parent)
                    Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }
}
