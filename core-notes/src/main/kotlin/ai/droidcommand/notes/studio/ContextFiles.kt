package ai.droidcommand.notes.studio

/**
 * AI context files that coding assistants read from a repo. Each kind knows the
 * path it is normally saved at, so export writes a file the tool will pick up.
 */
enum class ContextFileKind(val label: String, val exportPath: String) {
    CLAUDE_MD("CLAUDE.md", "CLAUDE.md"),
    AGENTS_MD("AGENTS.md", "AGENTS.md"),
    CURSOR_RULES(".cursorrules", ".cursorrules"),
    COPILOT_INSTRUCTIONS("copilot-instructions.md", ".github/copilot-instructions.md"),
    ;

    companion object {
        /** Guess the kind from a file name or path. Null when it is not a known context file. */
        fun fromFileName(path: String): ContextFileKind? {
            val name = path.substringAfterLast('/').lowercase()
            return when (name) {
                "claude.md" -> CLAUDE_MD
                "agents.md" -> AGENTS_MD
                ".cursorrules", "cursorrules.md" -> CURSOR_RULES
                "copilot-instructions.md" -> COPILOT_INSTRUCTIONS
                else -> null
            }
        }
    }
}

/** One heading + body pair in the structured editor. */
data class ContextSection(val title: String, val body: String)

/**
 * The structured form of a context file: an optional title and ordered sections.
 * [render] and [parse] round-trip for anything [render] produced.
 */
data class ContextDocument(val title: String, val sections: List<ContextSection>) {
    fun render(): String = buildString {
        if (title.isNotBlank()) append("# ").append(title.trim()).append("\n\n")
        sections.forEachIndexed { index, section ->
            append("## ").append(section.title.trim()).append('\n')
            val body = section.body.trimEnd()
            if (body.isNotEmpty()) append('\n').append(body).append('\n')
            if (index != sections.lastIndex) append('\n')
        }
    }.trimEnd() + "\n"

    fun withSection(index: Int, section: ContextSection): ContextDocument =
        copy(sections = sections.toMutableList().also { it[index] = section })

    fun addSection(section: ContextSection): ContextDocument = copy(sections = sections + section)

    fun removeSection(index: Int): ContextDocument =
        copy(sections = sections.filterIndexed { i, _ -> i != index })

    fun moveSection(from: Int, to: Int): ContextDocument {
        if (from !in sections.indices || to !in sections.indices) return this
        val list = sections.toMutableList()
        val item = list.removeAt(from)
        list.add(to, item)
        return copy(sections = list)
    }

    companion object {
        fun parse(markdown: String): ContextDocument {
            var title = ""
            val sections = mutableListOf<ContextSection>()
            var current: String? = null
            val body = StringBuilder()
            val preamble = StringBuilder()
            var inFence = false
            fun flush() {
                current?.let { sections += ContextSection(it, body.toString().trim('\n').trimEnd()) }
                body.clear()
            }
            markdown.replace("\r\n", "\n").lines().forEach { line ->
                if (line.trimStart().startsWith("```")) inFence = !inFence
                when {
                    !inFence && line.startsWith("# ") && title.isEmpty() && current == null -> title = line.removePrefix("# ").trim()
                    !inFence && line.startsWith("## ") -> {
                        flush()
                        current = line.removePrefix("## ").trim()
                    }
                    current == null -> preamble.append(line).append('\n')
                    else -> body.append(line).append('\n')
                }
            }
            flush()
            val pre = preamble.toString().trim()
            if (pre.isNotEmpty()) sections.add(0, ContextSection("Overview", pre))
            return ContextDocument(title, sections)
        }
    }
}

/** Starting points for each kind. Written for this app; not copied from any other product. */
object ContextTemplates {
    data class Template(val id: String, val name: String, val kind: ContextFileKind, val document: ContextDocument)

    private val common = listOf(
        ContextSection("Project overview", "What this repo is, who uses it, and the one thing a change must not break."),
        ContextSection("Build and test", "```sh\n./gradlew build\n./gradlew test\n```"),
        ContextSection("Code style", "- Follow the existing formatter.\n- Keep functions small and named for what they do."),
        ContextSection("Boundaries", "- Never commit secrets or signing keys.\n- Ask before deleting files or changing CI."),
    )

    val all: List<Template> = listOf(
        Template("claude-basic", "Claude: basic project", ContextFileKind.CLAUDE_MD, ContextDocument("Project guide", common)),
        Template(
            "agents-basic",
            "Agents: repo rules",
            ContextFileKind.AGENTS_MD,
            ContextDocument("AGENTS", common + ContextSection("Pull requests", "- One change per PR.\n- Describe how it was tested.")),
        ),
        Template(
            "cursor-basic",
            "Cursor rules",
            ContextFileKind.CURSOR_RULES,
            ContextDocument("", listOf(common[0], common[2], common[3])),
        ),
        Template(
            "copilot-basic",
            "Copilot instructions",
            ContextFileKind.COPILOT_INSTRUCTIONS,
            ContextDocument("Copilot instructions", listOf(common[0], common[1], common[2])),
        ),
    )

    fun forKind(kind: ContextFileKind): List<Template> = all.filter { it.kind == kind }
}

enum class LintSeverity { ERROR, WARNING, INFO }

data class LintIssue(val severity: LintSeverity, val line: Int, val rule: String, val message: String)

/**
 * Plain checks for AI context files. Line numbers are 1-based; 0 means the whole file.
 * Rules are deliberately simple and explainable so the user can see why each one fired.
 */
object ContextLinter {
    const val MAX_RECOMMENDED_CHARS = 20_000
    private val SECRET_PATTERNS = listOf(
        Regex("""(?i)\b(api[_-]?key|secret|password|token)\s*[:=]\s*\S{8,}"""),
        Regex("""\bsk-[A-Za-z0-9_\-]{16,}"""),
        Regex("""\bghp_[A-Za-z0-9]{20,}"""),
        Regex("""\bAKIA[0-9A-Z]{16}\b"""),
        Regex("""-----BEGIN [A-Z ]*PRIVATE KEY-----"""),
    )
    private val VAGUE = listOf("be careful", "do your best", "as needed", "etc.", "and so on", "if possible")

    fun lint(text: String, kind: ContextFileKind?): List<LintIssue> {
        val issues = mutableListOf<LintIssue>()
        val lines = text.replace("\r\n", "\n").lines()
        if (text.isBlank()) {
            return listOf(LintIssue(LintSeverity.ERROR, 0, "empty", "The file is empty, so the assistant gets no guidance."))
        }
        if (text.length > MAX_RECOMMENDED_CHARS) {
            issues += LintIssue(
                LintSeverity.WARNING,
                0,
                "too-long",
                "The file is ${text.length} characters. Long context files crowd out the real task; aim for under $MAX_RECOMMENDED_CHARS.",
            )
        }
        val isMarkdownKind = kind != ContextFileKind.CURSOR_RULES
        val headings = lines.withIndex().filter { (_, l) -> l.startsWith("#") }
        if (isMarkdownKind && headings.isEmpty()) {
            issues += LintIssue(LintSeverity.WARNING, 0, "no-headings", "Add ## headings so the assistant can find build, style, and boundary rules.")
        }
        val seen = mutableMapOf<String, Int>()
        var inFence = false
        var fenceStart = 0
        var lastLevel = 0
        lines.forEachIndexed { i, line ->
            val n = i + 1
            if (line.trimStart().startsWith("```")) {
                inFence = !inFence
                if (inFence) fenceStart = n
            }
            if (!inFence && line.startsWith("#")) {
                val level = line.takeWhile { it == '#' }.length
                val title = line.drop(level).trim().lowercase()
                if (title.isEmpty()) issues += LintIssue(LintSeverity.WARNING, n, "empty-heading", "Heading has no text.")
                seen[title]?.let { first ->
                    issues += LintIssue(LintSeverity.WARNING, n, "duplicate-heading", "\"$title\" is already a heading on line $first.")
                } ?: run { if (title.isNotEmpty()) seen[title] = n }
                if (lastLevel != 0 && level > lastLevel + 1) {
                    issues += LintIssue(LintSeverity.INFO, n, "heading-skip", "Heading jumps from level $lastLevel to $level.")
                }
                lastLevel = level
            }
            SECRET_PATTERNS.firstOrNull { it.containsMatchIn(line) }?.let {
                issues += LintIssue(LintSeverity.ERROR, n, "secret", "This line looks like a key or password. Remove it; context files get committed.")
            }
            if (line.length > 400) issues += LintIssue(LintSeverity.INFO, n, "long-line", "Very long line; split it into bullets.")
            VAGUE.firstOrNull { line.lowercase().contains(it) }?.let { phrase ->
                issues += LintIssue(LintSeverity.INFO, n, "vague", "\"$phrase\" is vague. Say exactly what to do.")
            }
            if (line.contains("TODO", ignoreCase = false)) issues += LintIssue(LintSeverity.INFO, n, "todo", "Unfinished TODO left in the file.")
        }
        if (inFence) issues += LintIssue(LintSeverity.ERROR, fenceStart, "unclosed-fence", "Code fence opened here is never closed.")
        val lower = text.lowercase()
        if (isMarkdownKind && listOf("test", "build", "gradle", "npm", "make").none { lower.contains(it) }) {
            issues += LintIssue(LintSeverity.INFO, 0, "no-commands", "No build or test command found. Tell the assistant how to check its work.")
        }
        return issues.sortedWith(compareBy({ it.severity.ordinal }, { it.line }))
    }
}
