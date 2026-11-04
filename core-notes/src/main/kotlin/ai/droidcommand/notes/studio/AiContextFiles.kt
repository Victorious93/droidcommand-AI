package ai.droidcommand.notes.studio

/** The AI context files the studio knows how to write and check. */
enum class AiContextKind(val displayName: String, val fileName: String, val repoPath: String) {
    CLAUDE("CLAUDE.md", "CLAUDE.md", "CLAUDE.md"),
    AGENTS("AGENTS.md", "AGENTS.md", "AGENTS.md"),
    CURSOR_RULES(".cursorrules", ".cursorrules", ".cursorrules"),
    COPILOT("copilot-instructions.md", "copilot-instructions.md", ".github/copilot-instructions.md"),
    ;

    /** Where the file is saved inside the notes vault. Vault names cannot start with a dot, so `.cursorrules` is stored as `cursorrules.md`. */
    val vaultPath: String get() = "AI Context/" + fileName.removePrefix(".").let { if (it.endsWith(".md")) it else "$it.md" }

    val recommendedSections: List<String>
        get() = when (this) {
            CURSOR_RULES -> listOf("Project", "Code style", "Rules")
            else -> listOf("Project overview", "Build and test", "Code style", "Rules")
        }
}

/** One `## ` section of a context file. [title] is empty for text before the first heading. */
data class ContextSection(val title: String, val body: String)

/** A context file split into a title line and sections, so the structured editor can edit one part at a time. */
data class ContextDocument(val title: String, val sections: List<ContextSection>) {
    fun render(): String = buildString {
        if (title.isNotBlank()) append("# ").append(title.trim()).append("\n\n")
        sections.forEach { section ->
            if (section.title.isNotBlank()) append("## ").append(section.title.trim()).append("\n\n")
            val body = section.body.trim()
            if (body.isNotEmpty()) append(body).append("\n\n")
        }
    }.trimEnd() + "\n"

    fun withSection(index: Int, section: ContextSection): ContextDocument =
        copy(sections = sections.toMutableList().also { it[index] = section })

    fun addSection(title: String): ContextDocument = copy(sections = sections + ContextSection(title, ""))

    fun removeSection(index: Int): ContextDocument = copy(sections = sections.filterIndexed { i, _ -> i != index })

    companion object {
        fun parse(text: String): ContextDocument {
            var title = ""
            val sections = mutableListOf<ContextSection>()
            var currentTitle: String? = null
            val body = StringBuilder()
            fun flush() {
                if (currentTitle != null || body.isNotBlank()) sections += ContextSection(currentTitle ?: "", body.toString().trim())
                body.clear()
            }
            text.replace("\r\n", "\n").lines().forEach { line ->
                when {
                    title.isEmpty() && currentTitle == null && body.isBlank() && line.startsWith("# ") -> title = line.removePrefix("# ").trim()
                    line.startsWith("## ") -> {
                        flush()
                        currentTitle = line.removePrefix("## ").trim()
                    }
                    else -> body.append(line).append('\n')
                }
            }
            flush()
            return ContextDocument(title, sections)
        }
    }
}

object AiContextTemplates {
    fun template(kind: AiContextKind, projectName: String = "My project"): String {
        val name = projectName.trim().ifEmpty { "My project" }
        return when (kind) {
            AiContextKind.CURSOR_RULES -> """
                # $name rules

                ## Project
                What this project is, in two or three sentences.

                ## Code style
                - Follow the existing formatting and naming.
                - Keep functions small and named for what they do.

                ## Rules
                - Run the tests before saying a change is done.
                - Never commit secrets or API keys.
            """.trimIndent() + "\n"
            else -> """
                # $name

                ## Project overview
                What this project is and who uses it.

                ## Build and test
                - Build: `./gradlew build`
                - Test: `./gradlew test`

                ## Code style
                - Follow the existing formatting and naming.

                ## Rules
                - Ask before deleting files or changing public APIs.
                - Never commit secrets or API keys.
            """.trimIndent() + "\n"
        }
    }
}

enum class LintSeverity { ERROR, WARNING, INFO }

data class LintIssue(val severity: LintSeverity, val line: Int, val message: String)

/** Checks an AI context file for problems an assistant reading it would trip on. Line numbers are 1-based; 0 means the whole file. */
object AiContextLinter {
    const val MAX_CHARS = 20_000
    const val MAX_LINE = 400

    private val secretPatterns = listOf(
        Regex("""sk-[A-Za-z0-9_-]{20,}"""),
        Regex("""ghp_[A-Za-z0-9]{30,}"""),
        Regex("""github_pat_[A-Za-z0-9_]{30,}"""),
        Regex("""AKIA[0-9A-Z]{16}"""),
        Regex("""xox[abpr]-[A-Za-z0-9-]{10,}"""),
        Regex("""-----BEGIN [A-Z ]*PRIVATE KEY-----"""),
        Regex("""(?i)(api[_-]?key|secret|password|token)\s*[:=]\s*['"]?[A-Za-z0-9_\-/+]{12,}"""),
    )
    private val placeholder = Regex("""(?i)\b(TODO|TBD|FIXME)\b|<[a-z ]+here>|lorem ipsum""")

    fun lint(kind: AiContextKind, text: String): List<LintIssue> {
        val issues = mutableListOf<LintIssue>()
        if (text.isBlank()) return listOf(LintIssue(LintSeverity.ERROR, 0, "The file is empty."))
        val lines = text.replace("\r\n", "\n").lines()
        if (text.length > MAX_CHARS) issues += LintIssue(LintSeverity.WARNING, 0, "The file is ${text.length} characters; assistants may cut off anything past about $MAX_CHARS.")
        if (kind != AiContextKind.CURSOR_RULES && lines.none { it.startsWith("#") }) {
            issues += LintIssue(LintSeverity.WARNING, 0, "There are no headings. Split the file into sections with ## headings.")
        }
        val seen = mutableMapOf<String, Int>()
        lines.forEachIndexed { i, line ->
            val n = i + 1
            if (secretPatterns.any { it.containsMatchIn(line) }) issues += LintIssue(LintSeverity.ERROR, n, "This line looks like a secret or key. Remove it; context files are shared with the assistant and often committed.")
            if (placeholder.containsMatchIn(line)) issues += LintIssue(LintSeverity.INFO, n, "Placeholder text left in.")
            if (line.length > MAX_LINE) issues += LintIssue(LintSeverity.INFO, n, "Very long line (${line.length} characters).")
            if (line.startsWith("## ")) {
                val key = line.removePrefix("## ").trim().lowercase()
                seen[key]?.let { first -> issues += LintIssue(LintSeverity.WARNING, n, "Duplicate heading; first used on line $first.") } ?: seen.put(key, n)
            }
        }
        val headings = seen.keys
        kind.recommendedSections.filter { wanted -> headings.none { it.contains(wanted.lowercase()) } }.forEach {
            issues += LintIssue(LintSeverity.INFO, 0, "Consider adding a \"$it\" section.")
        }
        return issues.sortedWith(compareBy({ it.severity.ordinal }, { it.line }))
    }
}
