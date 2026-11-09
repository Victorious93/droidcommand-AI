package ai.droidcommand.skills

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readText

/** A skill file found and parsed under a discovery root, with the path it came from. */
data class DiscoveredSkill(val sourcePath: String, val manifest: SkillManifest)

/** A skill file that was found but could not be read or parsed — recorded, never thrown. */
data class SkillDiscoveryFailure(val sourcePath: String, val reason: String)

/**
 * The result of a [SkillDiscovery.discover] walk: everything that parsed, and
 * everything that was found but failed, each with its source path. One bad file
 * never aborts the walk, so a single malformed skill cannot hide every other
 * skill in the tree.
 */
data class SkillDiscoveryReport(
    val discovered: List<DiscoveredSkill>,
    val failures: List<SkillDiscoveryFailure>,
)

/**
 * Walks a directory tree for packaged skill files (default name `SKILL.md`,
 * the one-directory-per-skill layout) and parses each with [SkillManifestParser].
 * Pure `java.nio.file` — no new dependency, consistent with the rest of
 * `core-skills`.
 *
 * Discovery only *reads and parses*; it installs nothing and grants nothing. A
 * caller that wants the results in a [SkillRegistry] installs them itself (they
 * then start disabled, like any install), so "found on disk" never means
 * "enabled" — the same keep-untrusted-imports-disabled posture the registry
 * documents. Results are returned sorted by path for determinism.
 */
class SkillDiscovery(
    private val fileName: String = DEFAULT_SKILL_FILE_NAME,
    private val parser: SkillManifestParser = SkillManifestParser(),
) {
    /**
     * Finds every file named [fileName] anywhere under [root] and parses it.
     * A non-existent or non-directory [root] yields an empty report with one
     * failure naming it, not an exception — a missing skills folder is a normal,
     * recoverable state, not a crash.
     */
    fun discover(root: Path): SkillDiscoveryReport {
        if (!root.isDirectory()) {
            return SkillDiscoveryReport(
                discovered = emptyList(),
                failures = listOf(SkillDiscoveryFailure(root.toString(), "skills root does not exist or is not a directory")),
            )
        }

        val discovered = mutableListOf<DiscoveredSkill>()
        val failures = mutableListOf<SkillDiscoveryFailure>()

        val skillFiles = try {
            Files.walk(root).use { stream ->
                stream.filter { it.isRegularFileNamed(fileName) }.sorted().toList()
            }
        } catch (e: IOException) {
            return SkillDiscoveryReport(
                discovered = emptyList(),
                failures = listOf(SkillDiscoveryFailure(root.toString(), "failed to walk skills root: ${e.message}")),
            )
        }

        for (file in skillFiles) {
            val path = file.toString()
            val text = try {
                file.readText()
            } catch (e: IOException) {
                failures += SkillDiscoveryFailure(path, "failed to read file: ${e.message}")
                continue
            }
            when (val result = parser.parse(text)) {
                is SkillParseResult.Success -> discovered += DiscoveredSkill(path, result.manifest)
                is SkillParseResult.Failure -> failures += SkillDiscoveryFailure(path, result.reason)
            }
        }

        return SkillDiscoveryReport(discovered = discovered, failures = failures)
    }

    private fun Path.isRegularFileNamed(name: String): Boolean =
        this.name == name && Files.isRegularFile(this)

    companion object {
        const val DEFAULT_SKILL_FILE_NAME = "SKILL.md"
    }
}
