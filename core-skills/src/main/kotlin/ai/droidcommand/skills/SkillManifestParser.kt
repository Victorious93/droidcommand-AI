package ai.droidcommand.skills

/** The outcome of parsing a `SKILL.md` document — a real [SkillManifest] or an explicit, non-throwing reason it could not be parsed. */
sealed class SkillParseResult {
    data class Success(val manifest: SkillManifest) : SkillParseResult()

    data class Failure(val reason: String) : SkillParseResult()
}

/**
 * Parses a packaged skill document (`SKILL.md`): a YAML-style frontmatter block
 * delimited by lines of exactly `---`, followed by the markdown instruction
 * body. No YAML library is pulled in — the frontmatter this understands is the
 * small, well-defined subset packaged skills actually use (`key: value` scalars
 * and a `tools` list), parsed by hand, the same dependency-light approach
 * `core-tools-android`'s `uiautomator`-XML parsing and `AdbDeviceController`'s
 * `dumpsys` parsing take. Anything outside that subset is reported as a
 * [SkillParseResult.Failure], never silently half-parsed.
 *
 * Example input:
 * ```
 * ---
 * name: summarize
 * description: Summarize a document into bullet points
 * tools: [read_file, web_search]
 * version: 1.2
 * ---
 * # Summarize
 * Read the document and produce at most five bullets.
 * ```
 *
 * Recognised frontmatter keys: `name` (required), `description` (required),
 * `tools` (optional — inline `[a, b]`, bare `a, b`, or a following block of
 * `- a` lines), `version` (optional). An unknown key is ignored rather than
 * failing, so a newer skill file still loads on an older build (its extra keys
 * simply aren't surfaced). A duplicate recognised key is a failure — silently
 * keeping the first or last would misrepresent the file.
 */
class SkillManifestParser {
    fun parse(document: String): SkillParseResult {
        val normalized = document.replace("\r\n", "\n").replace("\r", "\n")
        val lines = normalized.split("\n")

        var i = 0
        // Skip a leading blank line or two before the opening fence, but nothing else.
        while (i < lines.size && lines[i].isBlank()) i++
        if (i >= lines.size || lines[i].trim() != FENCE) {
            return SkillParseResult.Failure("missing frontmatter: the document must start with a '---' fence")
        }
        i++ // past opening fence

        val scalars = mutableMapOf<String, String>()
        var tools: List<String>? = null
        val seenKeys = mutableSetOf<String>()
        var closed = false

        while (i < lines.size) {
            val line = lines[i]
            if (line.trim() == FENCE) {
                closed = true
                i++
                break
            }
            if (line.isBlank()) {
                i++
                continue
            }
            val colon = line.indexOf(':')
            if (colon <= 0) {
                return SkillParseResult.Failure("malformed frontmatter line (expected 'key: value'): '$line'")
            }
            val key = line.substring(0, colon).trim()
            val rawValue = line.substring(colon + 1).trim()
            if (!seenKeys.add(key)) {
                return SkillParseResult.Failure("duplicate frontmatter key '$key'")
            }
            when (key) {
                "tools" -> {
                    if (rawValue.isEmpty()) {
                        // A block list: consume the following '- item' lines.
                        val collected = mutableListOf<String>()
                        while (i + 1 < lines.size && lines[i + 1].trimStart().startsWith("- ")) {
                            collected += lines[i + 1].trimStart().removePrefix("- ").trim()
                            i++
                        }
                        tools = collected.filter { it.isNotEmpty() }
                    } else {
                        tools = parseInlineList(rawValue)
                    }
                }
                else -> scalars[key] = rawValue
            }
            i++
        }

        if (!closed) {
            return SkillParseResult.Failure("unterminated frontmatter: no closing '---' fence")
        }

        val name = scalars["name"]?.takeIf { it.isNotBlank() }
            ?: return SkillParseResult.Failure("frontmatter is missing required key 'name'")
        val description = scalars["description"]?.takeIf { it.isNotBlank() }
            ?: return SkillParseResult.Failure("frontmatter is missing required key 'description'")
        if (!SkillManifest.NAME_PATTERN.matches(name)) {
            return SkillParseResult.Failure(
                "skill name must be a slug (letters, digits, '-', '_', '.'; starting with a letter), was '$name'",
            )
        }

        val instructions = lines.subList(i, lines.size).joinToString("\n").trim()

        return SkillParseResult.Success(
            SkillManifest(
                name = name,
                description = description,
                declaredTools = tools ?: emptyList(),
                version = scalars["version"]?.takeIf { it.isNotBlank() },
                instructions = instructions,
            ),
        )
    }

    private fun parseInlineList(raw: String): List<String> {
        val inner = raw.removePrefix("[").removeSuffix("]")
        return inner.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    private companion object {
        const val FENCE = "---"
    }
}
