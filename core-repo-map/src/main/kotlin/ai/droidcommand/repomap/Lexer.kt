package ai.droidcommand.repomap

internal enum class TokKind { IDENT, PUNCT, NEWLINE }

internal class Tok(val kind: TokKind, val text: String, val line: Int)

/** Per-language lexical rules. Only what the tokenizer needs; structural rules live in [LexicalSourceParser]. */
internal class Lang(
    val keywords: Set<String>,
    val lineComment: String = "//",
    val blockComments: Boolean = true,
    val nestedBlockComments: Boolean = false,
    val tripleQuotes: List<String> = emptyList(),
    /** Backslash escapes inside triple-quoted strings (Java text blocks, Python) — not Kotlin raw strings. */
    val tripleEscapes: Boolean = false,
    val backtickIsIdent: Boolean = false,
    val backtickIsString: Boolean = false,
    /** Kotlin `$name` / `${expr}` inside strings refer to code, so they are surfaced as identifiers. */
    val dollarTemplates: Boolean = false,
    val emitNewlines: Boolean = false,
    val identExtra: String = "",
    val stringPrefixes: Set<String> = emptySet(),
)

private const val PUNCTUATION = "{}()[];=.,:<>@*"

/**
 * A tokenizer, not a parser: it recognizes exactly the things that make regex-over-lines wrong — line and
 * block comments (nested for Kotlin), quoted strings, raw/triple-quoted strings and text blocks, JS template
 * literals, Python string prefixes, Kotlin backtick identifiers — and emits only identifiers and the few
 * punctuation marks the structural pass needs. Text inside comments and strings therefore never becomes a
 * token (except Kotlin template references, which are code).
 *
 * A single-line string that is never closed ends at the end of its line, so a stray quote (a regex literal
 * such as `/"/`, say) can damage at most that one line rather than swallowing the rest of the file.
 */
internal fun lex(src: String, lang: Lang): List<Tok> {
    val out = ArrayList<Tok>(src.length / 5 + 16)
    val n = src.length
    var i = 0
    var line = 1
    var lastIdentEnd = -1

    fun isIdentStart(c: Char) = c == '_' || Character.isLetter(c) || lang.identExtra.indexOf(c) >= 0
    fun isIdentPart(c: Char) = isIdentStart(c) || Character.isDigit(c)

    fun emitTemplateRefs(body: String, atLine: Int) {
        var k = 0
        while (k < body.length) {
            if (body[k] == '$' && k + 1 < body.length) {
                if (body[k + 1] == '{') {
                    var depth = 1
                    var end = k + 2
                    while (end < body.length && depth > 0) {
                        if (body[end] == '{') depth++ else if (body[end] == '}') depth--
                        end++
                    }
                    var m = k + 2
                    while (m < end - 1) {
                        if (isIdentStart(body[m])) {
                            val s = m
                            while (m < end - 1 && isIdentPart(body[m])) m++
                            out.add(Tok(TokKind.IDENT, body.substring(s, m), atLine))
                        } else {
                            m++
                        }
                    }
                    k = end
                    continue
                } else if (isIdentStart(body[k + 1]) && body[k + 1] != '$') {
                    var m = k + 1
                    while (m < body.length && isIdentPart(body[m])) m++
                    out.add(Tok(TokKind.IDENT, body.substring(k + 1, m), atLine))
                    k = m
                    continue
                }
            }
            k++
        }
    }

    while (i < n) {
        val c = src[i]
        when {
            c == '\n' -> {
                if (lang.emitNewlines) out.add(Tok(TokKind.NEWLINE, "\n", line))
                line++
                i++
            }
            c.isWhitespace() -> i++
            lang.lineComment.isNotEmpty() && src.startsWith(lang.lineComment, i) -> {
                while (i < n && src[i] != '\n') i++
            }
            lang.blockComments && src.startsWith("/*", i) -> {
                var depth = 1
                i += 2
                while (i < n && depth > 0) {
                    when {
                        src.startsWith("*/", i) -> {
                            depth--
                            i += 2
                        }
                        lang.nestedBlockComments && src.startsWith("/*", i) -> {
                            depth++
                            i += 2
                        }
                        else -> {
                            if (src[i] == '\n') line++
                            i++
                        }
                    }
                }
            }
            c == '"' || c == '\'' || (c == '`' && lang.backtickIsString) -> {
                if (lang.stringPrefixes.isNotEmpty()) {
                    val last = out.lastOrNull()
                    if (last != null && last.kind == TokKind.IDENT && lastIdentEnd == i && last.text.lowercase() in lang.stringPrefixes) {
                        out.removeAt(out.size - 1)
                    }
                }
                val startLine = line
                val triple = lang.tripleQuotes.firstOrNull { src.startsWith(it, i) }
                if (triple != null) {
                    val bodyStart = i + 3
                    var j = bodyStart
                    while (j < n && !src.startsWith(triple, j)) {
                        if (lang.tripleEscapes && src[j] == '\\' && j + 1 < n) {
                            if (src[j + 1] == '\n') line++
                            j += 2
                            continue
                        }
                        if (src[j] == '\n') line++
                        j++
                    }
                    if (lang.dollarTemplates) emitTemplateRefs(src.substring(bodyStart, minOf(j, n)), startLine)
                    i = minOf(n, j + 3)
                } else {
                    var j = i + 1
                    while (j < n) {
                        val ch = src[j]
                        if (ch == '\\' && j + 1 < n) {
                            if (src[j + 1] == '\n') line++
                            j += 2
                            continue
                        }
                        if (ch == c) break
                        if (ch == '\n' && c != '`') break
                        if (ch == '\n') line++
                        j++
                    }
                    if (lang.dollarTemplates && c == '"') emitTemplateRefs(src.substring(i + 1, minOf(j, n)), startLine)
                    i = if (j < n && src[j] == c) j + 1 else j
                }
            }
            c == '`' && lang.backtickIsIdent -> {
                var j = i + 1
                while (j < n && src[j] != '`' && src[j] != '\n') j++
                out.add(Tok(TokKind.IDENT, src.substring(i + 1, j), line))
                i = if (j < n && src[j] == '`') j + 1 else j
            }
            isIdentStart(c) -> {
                val start = i
                i++
                while (i < n && isIdentPart(src[i])) i++
                out.add(Tok(TokKind.IDENT, src.substring(start, i), line))
                lastIdentEnd = i
            }
            Character.isDigit(c) -> {
                i++
                while (i < n && (Character.isLetterOrDigit(src[i]) || src[i] == '_' || (src[i] == '.' && i + 1 < n && Character.isDigit(src[i + 1])))) i++
            }
            else -> {
                if (PUNCTUATION.indexOf(c) >= 0) out.add(Tok(TokKind.PUNCT, c.toString(), line))
                i++
            }
        }
    }
    return out
}
