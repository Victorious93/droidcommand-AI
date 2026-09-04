package ai.droidforge.build

import java.io.File

/**
 * Checks whether an executable file with the given name exists in any
 * directory listed in `PATH`. Deliberately does not spawn a process (no
 * `which`, no `command -v`) — this is a pure filesystem existence check,
 * so it carries none of the risk of shell execution and stays testable
 * with a fake `PATH` pointing at a fixture directory.
 */
class PathExecutableDetector(private val pathEnv: () -> String? = { System.getenv("PATH") }) {
    fun isOnPath(executableName: String): Boolean {
        val entries = pathEnv()?.split(File.pathSeparatorChar) ?: return false
        return entries.any { dir ->
            val candidate = File(dir, executableName)
            candidate.isFile && candidate.canExecute()
        }
    }
}
