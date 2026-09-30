package ai.droidcommand.repomap

import java.nio.file.Path

/** One declaration found in a file. [line] is 1-based; [kind] is a short label such as `class`, `fun`, `method`, `def`. */
data class Definition(val name: String, val kind: String, val line: Int)

/**
 * What a parser extracted from one file: what it [definitions] declares, and how often each identifier
 * is used in code ([references]; identifiers inside comments and string literals are not counted, and a
 * declaration's own name is not counted as a use of itself).
 */
class ParsedFile(val definitions: List<Definition>, val references: Map<String, Int>)

/**
 * The seam between "reading source" and "ranking a repository". [LexicalSourceParser] is the built-in
 * implementation; a tree-sitter- or compiler-backed parser can replace it without touching ranking or
 * rendering, which only ever see [ParsedFile].
 */
interface SourceParser {
    fun supports(path: Path): Boolean

    fun parse(path: Path, content: String): ParsedFile
}
