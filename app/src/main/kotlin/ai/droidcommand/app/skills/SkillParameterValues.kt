package ai.droidcommand.app.skills

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Saved Skill-file parameter values. The on-disk format is one `skill|param=value` line
 * (the historic file name ends in `.json` but the contents are not JSON).
 */
object SkillParameterValues {
    fun load(file: Path): Map<String, Map<String, String>> {
        if (!Files.isRegularFile(file)) return emptyMap()
        val text = runCatching { file.readText() }.getOrNull() ?: return emptyMap()
        val values = linkedMapOf<String, MutableMap<String, String>>()
        text.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
            val bar = trimmed.indexOf('|')
            val eq = trimmed.indexOf('=')
            if (bar <= 0 || eq <= bar) return@forEach
            val skill = trimmed.substring(0, bar)
            val param = trimmed.substring(bar + 1, eq)
            val value = trimmed.substring(eq + 1)
            values.getOrPut(skill) { linkedMapOf() }[param] = value
        }
        return values
    }

    fun save(file: Path, values: Map<String, Map<String, String>>) {
        val body = buildString {
            appendLine("# skill parameter values")
            values.entries.sortedBy { it.key }.forEach { (skill, map) ->
                map.entries.sortedBy { it.key }.forEach { (param, value) ->
                    append(skill).append('|').append(param).append('=').append(value.replace('\n', ' ')).append('\n')
                }
            }
        }
        val parent = file.parent
        if (parent != null) Files.createDirectories(parent)
        file.writeText(body)
    }
}
