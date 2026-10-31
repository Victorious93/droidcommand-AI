package ai.droidcommand.notes

data class WikiLink(
    val target: String,
    val alias: String? = null,
    val start: Int,
    val end: Int,
)

data class NoteFrontmatter(
    val title: String? = null,
    val tags: List<String> = emptyList(),
    val extras: Map<String, String> = emptyMap(),
)

data class NoteDocument(
    val relativePath: String,
    val title: String,
    val tags: Set<String>,
    val links: List<WikiLink>,
    val frontmatter: NoteFrontmatter,
    val body: String,
    val raw: String,
) {
    /** Filename without `.md`, used to resolve `[[wikilinks]]` when there is no title. */
    val stem: String
        get() = relativePath.substringAfterLast('/').removeSuffix(".md")
}

object NoteParser {
    private val WIKILINK = Regex("""\[\[([^\]|#]+?)(?:\|([^\]]+))?]]""")
    private val HASH_TAG = Regex("""(?<![A-Za-z0-9_/])#([A-Za-z][A-Za-z0-9_/-]{0,47})""")
    private val HEADING = Regex("""^#{1,6}\s+\S.*""")

    fun parse(relativePath: String, raw: String): NoteDocument {
        val (frontmatter, body) = splitFrontmatter(raw)
        val title = frontmatter.title?.takeIf { it.isNotBlank() } ?: titleFromBody(body)
            ?: relativePath.substringAfterLast('/').removeSuffix(".md")
        val tags = (frontmatter.tags + hashTags(body)).map { it.trim().trimStart('#') }
            .filter { it.isNotEmpty() }
            .map { it.lowercase() }
            .toSet()
        return NoteDocument(
            relativePath = relativePath,
            title = title,
            tags = tags,
            links = wikiLinks(body),
            frontmatter = frontmatter,
            body = body,
            raw = raw,
        )
    }

    fun splitFrontmatter(raw: String): Pair<NoteFrontmatter, String> {
        val lines = raw.lines()
        if (lines.isEmpty() || lines.first().trim() != "---") {
            return NoteFrontmatter() to raw
        }
        val end = (1 until lines.size).firstOrNull { lines[it].trim() == "---" } ?: return NoteFrontmatter() to raw
        val yaml = lines.subList(1, end).joinToString("\n")
        val body = lines.drop(end + 1).joinToString("\n").trimStart('\n')
        return parseFrontmatter(yaml) to body
    }

    fun parseFrontmatter(yaml: String): NoteFrontmatter {
        val extras = linkedMapOf<String, String>()
        var title: String? = null
        val tags = mutableListOf<String>()
        var inTags = false
        for (rawLine in yaml.lines()) {
            val line = rawLine.trimEnd()
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            if (inTags && (trimmed.startsWith("- ") || trimmed.startsWith("* "))) {
                tags += unquote(trimmed.drop(2).trim())
                continue
            }
            inTags = false
            val colon = trimmed.indexOf(':')
            if (colon <= 0) continue
            val key = trimmed.substring(0, colon).trim()
            val value = trimmed.substring(colon + 1).trim()
            when (key) {
                "title" -> title = unquote(value).takeIf { it.isNotEmpty() }
                "tags" -> {
                    if (value.isEmpty()) {
                        inTags = true
                    } else {
                        tags += parseTagList(value)
                    }
                }
                else -> if (value.isNotEmpty()) extras[key] = unquote(value)
            }
        }
        return NoteFrontmatter(title = title, tags = tags.filter { it.isNotEmpty() }, extras = extras)
    }

    fun wikiLinks(body: String): List<WikiLink> {
        val usable = maskCodeFences(body)
        return WIKILINK.findAll(usable).map { match ->
            WikiLink(
                target = match.groupValues[1].trim(),
                alias = match.groupValues[2].trim().takeIf { it.isNotEmpty() },
                start = match.range.first,
                end = match.range.last + 1,
            )
        }.toList()
    }

    private fun hashTags(body: String): List<String> {
        val usable = maskCodeFences(body)
        return HASH_TAG.findAll(usable).map { it.groupValues[1] }.toList()
    }

    private fun titleFromBody(body: String): String? {
        for (line in body.lines()) {
            val trimmed = line.trim()
            if (HEADING.matches(trimmed)) {
                return trimmed.dropWhile { it == '#' }.trim().takeIf { it.isNotEmpty() }
            }
            if (trimmed.isNotEmpty()) return null
        }
        return null
    }

    private fun parseTagList(value: String): List<String> {
        val inner = value.trim().removePrefix("[").removeSuffix("]").trim()
        if (inner.isEmpty()) return emptyList()
        return inner.split(',', ' ').map { unquote(it.trim().trimStart('#')) }.filter { it.isNotEmpty() }
    }

    private fun unquote(value: String): String {
        val t = value.trim()
        return if (t.length >= 2 && ((t.startsWith('"') && t.endsWith('"')) || (t.startsWith('\'') && t.endsWith('\'')))) {
            t.substring(1, t.length - 1)
        } else {
            t
        }
    }

    /** Replaces fenced code with spaces so `[[links]]` and `#tags` inside code are ignored. */
    private fun maskCodeFences(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        var inFence = false
        val lines = text.lines()
        for (line in lines) {
            if (i > 0) out.append('\n')
            if (line.trimStart().startsWith("```")) {
                inFence = !inFence
                out.append(" ".repeat(line.length))
            } else if (inFence) {
                out.append(" ".repeat(line.length))
            } else {
                out.append(line)
            }
            i++
        }
        return out.toString()
    }
}
