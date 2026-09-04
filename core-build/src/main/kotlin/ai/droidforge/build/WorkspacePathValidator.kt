package ai.droidforge.build

import java.nio.file.Path

class PathSecurityViolation(message: String) : SecurityException(message)

/**
 * Resolves a path strictly inside one of [authorizedRoots] — the only
 * mechanism in this module that turns a relative path string into a real
 * filesystem [Path]. A "../" traversal or an absolute path that would
 * normalize outside the given root is rejected before any filesystem call
 * is made, not detected after the fact.
 */
class WorkspacePathValidator(private val authorizedRoots: List<Path>) {
    fun resolve(root: Path, relativePath: String): Path {
        val normalizedRoot = root.normalize()
        require(authorizedRoots.any { normalizedRoot.startsWith(it.normalize()) }) {
            "Root '$root' is not under any authorized root"
        }

        val candidate = normalizedRoot.resolve(relativePath).normalize()
        if (!candidate.startsWith(normalizedRoot)) {
            throw PathSecurityViolation("Path '$relativePath' escapes workspace root '$normalizedRoot'")
        }
        return candidate
    }
}
