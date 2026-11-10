package ai.droidcommand.app.skills

import ai.droidcommand.skills.BoundSkillPrompt
import ai.droidcommand.skills.FileSkillStateStore
import ai.droidcommand.skills.SkillDiscovery
import ai.droidcommand.skills.SkillRegistry
import ai.droidcommand.skills.assembleBoundSkills
import java.nio.file.Path

/** Shared locations for skill files, enable-state, and saved parameter values under the app files dir. */
object SkillFilesLocations {
    fun skillsDir(filesDir: Path): Path = filesDir.resolve("skills")

    fun stateFile(filesDir: Path): Path = filesDir.resolve("skill-state.json")

    fun valuesFile(filesDir: Path): Path = filesDir.resolve("skill-param-values.json")
}

/**
 * Loads enabled skill files from disk, binds saved parameter values, and assembles the
 * system-prompt block used by phone Chat. Missing required parameters skip that skill;
 * the skip list is returned so Chat can show a note instead of crashing.
 */
object SkillFilesPrompt {
    fun assemble(filesDir: Path): BoundSkillPrompt {
        val skillsDir = SkillFilesLocations.skillsDir(filesDir)
        val report = SkillDiscovery().discover(skillsDir)
        val registry = SkillRegistry()
        report.discovered.forEach { discovered -> registry.install(discovered.manifest) }
        registry.applyEnabledState(FileSkillStateStore(SkillFilesLocations.stateFile(filesDir)).load())
        val values = SkillParameterValues.load(SkillFilesLocations.valuesFile(filesDir))
        return assembleBoundSkills(registry.list()) { skill, param -> values[skill]?.get(param) }
    }
}
