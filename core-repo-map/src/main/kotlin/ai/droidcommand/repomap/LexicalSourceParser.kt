package ai.droidcommand.repomap

import java.nio.file.Path

/** A `{` that no declaration claimed (lambda, initializer, if/for/try block, top-level block) is code, so it counts as [FUNCTION]. */
private enum class Scope { TYPE, FUNCTION }

private enum class Family { KOTLIN, JAVA, JS, PYTHON }

private class Language(val family: Family, val extensions: Set<String>, val lang: Lang)

private val KOTLIN_KEYWORDS = setOf(
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface", "is", "null", "object", "package",
    "return", "super", "this", "throw", "true", "try", "typealias", "typeof", "val", "var", "when", "while", "by", "catch", "constructor",
    "finally", "get", "set", "import", "init", "where", "abstract", "actual", "annotation", "companion", "const", "crossinline", "data",
    "enum", "expect", "external", "final", "infix", "inline", "inner", "internal", "lateinit", "noinline", "open", "operator", "out",
    "override", "private", "protected", "public", "reified", "sealed", "suspend", "tailrec", "vararg", "it",
)

private val JAVA_MODIFIERS = setOf("public", "private", "protected", "static", "final", "abstract", "synchronized", "native", "default", "strictfp")
private val JAVA_PRIMITIVES = setOf("void", "int", "long", "short", "byte", "char", "float", "double", "boolean")
private val JAVA_KEYWORDS = setOf(
    "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const", "continue", "default", "do", "double",
    "else", "enum", "extends", "final", "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int", "interface",
    "long", "native", "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp", "super", "switch",
    "synchronized", "this", "throw", "throws", "transient", "try", "void", "volatile", "while", "true", "false", "null", "var", "record",
    "yield", "sealed", "permits",
)

private val JS_MODIFIERS = setOf("public", "private", "protected", "static", "async", "get", "set", "readonly", "abstract", "override")
private val JS_KEYWORDS = setOf(
    "break", "case", "catch", "class", "const", "continue", "debugger", "default", "delete", "do", "else", "enum", "export", "extends",
    "false", "finally", "for", "function", "if", "import", "in", "instanceof", "new", "null", "return", "super", "switch", "this",
    "throw", "true", "try", "typeof", "var", "void", "while", "with", "yield", "let", "static", "async", "await", "of", "get", "set",
    "interface", "type", "implements", "package", "private", "protected", "public", "readonly", "abstract", "declare", "namespace",
    "module", "as", "from", "undefined",
)

private val PYTHON_KEYWORDS = setOf(
    "False", "None", "True", "and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del", "elif", "else",
    "except", "finally", "for", "from", "global", "if", "import", "in", "is", "lambda", "nonlocal", "not", "or", "pass", "raise",
    "return", "try", "while", "with", "yield", "self", "cls", "match", "case",
)

private val LANGUAGES = listOf(
    Language(
        Family.KOTLIN,
        setOf("kt", "kts"),
        Lang(KOTLIN_KEYWORDS, nestedBlockComments = true, tripleQuotes = listOf("\"\"\""), backtickIsIdent = true, dollarTemplates = true),
    ),
    Language(
        Family.JAVA,
        setOf("java"),
        Lang(JAVA_KEYWORDS, tripleQuotes = listOf("\"\"\""), tripleEscapes = true, identExtra = "$"),
    ),
    Language(
        Family.JS,
        setOf("js", "jsx", "mjs", "cjs", "ts", "tsx"),
        Lang(JS_KEYWORDS, backtickIsString = true, identExtra = "$"),
    ),
    Language(
        Family.PYTHON,
        setOf("py"),
        Lang(
            PYTHON_KEYWORDS,
            lineComment = "#",
            blockComments = false,
            tripleQuotes = listOf("\"\"\"", "'''"),
            tripleEscapes = true,
            emitNewlines = true,
            stringPrefixes = setOf("r", "b", "u", "f", "rb", "br", "fr", "rf"),
        ),
    ),
)

/**
 * The built-in [SourceParser]: [lex] strips comments and strings, then a small structural pass tracks
 * braces/indentation to tell class/module-level declarations from function-local ones.
 *
 * It is **not** an AST parser (see docs/DEV_ASSIST.md for why none was adopted). It cannot resolve
 * overloads, imports or types, so a "reference" means only "this identifier appears in code". Known
 * approximations: Kotlin class-header `val` parameters and Java fields are not reported as definitions;
 * JS/TS object-literal methods are not definitions; a JS regex literal containing a quote can misread the
 * rest of that one line.
 */
class LexicalSourceParser : SourceParser {
    private fun languageOf(path: Path): Language? {
        val extension = path.fileName?.toString()?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return LANGUAGES.firstOrNull { extension in it.extensions }
    }

    override fun supports(path: Path): Boolean = languageOf(path) != null

    override fun parse(path: Path, content: String): ParsedFile {
        val language = languageOf(path) ?: return ParsedFile(emptyList(), emptyMap())
        val tokens = lex(content, language.lang)
        val definitions = ArrayList<Definition>()
        val declared = HashSet<Int>() // token indexes that are a declaration's own name
        when (language.family) {
            Family.KOTLIN -> braceLanguage(tokens, language.lang, Family.KOTLIN, definitions, declared)
            Family.JAVA -> braceLanguage(tokens, language.lang, Family.JAVA, definitions, declared)
            Family.JS -> braceLanguage(tokens, language.lang, Family.JS, definitions, declared)
            Family.PYTHON -> python(tokens, content, language.lang, definitions, declared)
        }
        val references = HashMap<String, Int>()
        for ((index, token) in tokens.withIndex()) {
            if (token.kind == TokKind.IDENT && index !in declared && token.text !in language.lang.keywords) {
                references.merge(token.text, 1, Int::plus)
            }
        }
        return ParsedFile(definitions, references)
    }

    // ------------------------------------------------------------ brace languages

    private fun braceLanguage(
        toks: List<Tok>,
        lang: Lang,
        family: Family,
        defs: MutableList<Definition>,
        declared: MutableSet<Int>,
    ) {
        var paren = 0
        val scopes = ArrayList<Scope>()
        var pending: Scope? = null

        fun isName(index: Int): Boolean {
            val t = toks.getOrNull(index) ?: return false
            return t.kind == TokKind.IDENT && t.text !in lang.keywords
        }

        fun define(nameIndex: Int, kind: String) {
            val t = toks[nameIndex]
            defs.add(Definition(t.text, kind, t.line))
            declared.add(nameIndex)
        }

        fun inFunction() = scopes.any { it == Scope.FUNCTION }

        fun prevText(i: Int) = toks.getOrNull(i - 1)?.text
        fun nextText(i: Int, k: Int = 1) = toks.getOrNull(i + k)?.text

        for (i in toks.indices) {
            val t = toks[i]
            if (t.kind == TokKind.PUNCT) {
                when (t.text) {
                    "(", "[" -> paren++
                    ")", "]" -> if (paren > 0) paren--
                    "{" -> {
                        scopes.add(pending ?: Scope.FUNCTION)
                        pending = null
                    }
                    "}" -> {
                        if (scopes.isNotEmpty()) scopes.removeAt(scopes.size - 1)
                        pending = null
                    }
                    ";" -> pending = null
                }
                continue
            }
            if (t.kind != TokKind.IDENT || paren != 0) continue
            val afterDotOrColon = prevText(i) == "." || prevText(i) == ":"

            when (family) {
                Family.KOTLIN -> when (t.text) {
                    "class", "interface" -> if (!afterDotOrColon) {
                        pending = Scope.TYPE
                        if (!inFunction() && isName(i + 1)) define(i + 1, t.text)
                    }
                    "object" -> if (!afterDotOrColon) {
                        pending = Scope.TYPE
                        if (!inFunction() && isName(i + 1)) define(i + 1, "object")
                    }
                    "typealias" -> if (!inFunction() && isName(i + 1)) define(i + 1, "typealias")
                    "fun" -> if (nextText(i) != "interface" && !afterDotOrColon) {
                        pending = Scope.FUNCTION
                        if (!inFunction()) kotlinFunctionName(toks, i, lang)?.let { define(it, "fun") }
                    }
                    "val", "var" -> if (!afterDotOrColon) {
                        pending = null
                        if (!inFunction()) kotlinPropertyName(toks, i, lang)?.let { define(it, t.text) }
                    }
                    "init", "constructor", "get", "set" ->
                        if (!afterDotOrColon && (nextText(i) == "{" || nextText(i) == "(")) pending = Scope.FUNCTION
                }
                Family.JAVA -> when {
                    t.text in setOf("class", "interface", "enum") && prevText(i) != "." -> {
                        pending = Scope.TYPE
                        if (isName(i + 1)) define(i + 1, t.text)
                    }
                    t.text == "record" && isName(i + 1) && (nextText(i, 2) == "(" || nextText(i, 2) == "<") -> {
                        pending = Scope.TYPE
                        define(i + 1, "record")
                    }
                    scopes.lastOrNull() == Scope.TYPE && isName(i) && nextText(i) == "(" && javaMethodContext(toks, i, lang) -> {
                        define(i, "method")
                        pending = Scope.FUNCTION
                    }
                }
                Family.JS -> when {
                    t.text == "class" && prevText(i) != "." -> {
                        pending = Scope.TYPE
                        if (isName(i + 1)) define(i + 1, "class")
                    }
                    (t.text == "interface" || t.text == "enum" || t.text == "namespace") && isName(i + 1) && !afterDotOrColon -> {
                        pending = Scope.TYPE
                        define(i + 1, t.text)
                    }
                    t.text == "type" && isName(i + 1) && (nextText(i, 2) == "=" || nextText(i, 2) == "<") && scopes.isEmpty() ->
                        define(i + 1, "type")
                    t.text == "function" && !afterDotOrColon -> {
                        pending = Scope.FUNCTION
                        val nameIndex = if (nextText(i) == "*") i + 2 else i + 1
                        if (!inFunction() && isName(nameIndex)) define(nameIndex, "function")
                    }
                    (t.text == "const" || t.text == "let" || t.text == "var") && scopes.isEmpty() && isName(i + 1) ->
                        define(i + 1, if (jsIsFunctionValue(toks, i + 2)) "function" else t.text)
                    scopes.lastOrNull() == Scope.TYPE && isName(i) && nextText(i) == "(" && jsMethodContext(toks, i) -> {
                        define(i, "method")
                        pending = Scope.FUNCTION
                    }
                }
                Family.PYTHON -> Unit
            }
        }
    }

    /** `fun <T> Recv<T>.name(`: the last identifier before the first `(` that is outside `<...>`. */
    private fun kotlinFunctionName(toks: List<Tok>, funIndex: Int, lang: Lang): Int? {
        var angle = 0
        var name: Int? = null
        for (j in funIndex + 1 until minOf(toks.size, funIndex + 80)) {
            val t = toks[j]
            when {
                t.kind == TokKind.PUNCT && t.text == "<" -> angle++
                t.kind == TokKind.PUNCT && t.text == ">" -> if (angle > 0) angle--
                t.kind == TokKind.PUNCT && t.text == "(" && angle == 0 -> return name
                t.kind == TokKind.PUNCT && (t.text == "{" || t.text == "=" || t.text == ";") -> return null
                t.kind == TokKind.IDENT && angle == 0 && t.text !in lang.keywords -> name = j
            }
        }
        return null
    }

    /** `val Recv.name`, `val <T> name`: the identifier after any generic list and receiver dots; null for `val (a, b) = ...`. */
    private fun kotlinPropertyName(toks: List<Tok>, valIndex: Int, lang: Lang): Int? {
        var j = valIndex + 1
        if (toks.getOrNull(j)?.text == "<") {
            var depth = 0
            while (j < toks.size) {
                if (toks[j].text == "<") depth++ else if (toks[j].text == ">") depth--
                j++
                if (depth == 0) break
            }
        }
        var name: Int? = null
        while (j < toks.size) {
            val t = toks[j]
            if (t.kind != TokKind.IDENT || t.text in lang.keywords) return name
            name = j
            if (toks.getOrNull(j + 1)?.text == ".") j += 2 else return name
        }
        return name
    }

    private fun javaMethodContext(toks: List<Tok>, i: Int, lang: Lang): Boolean {
        val prev = toks.getOrNull(i - 1) ?: return false
        if (prev.kind == TokKind.IDENT) {
            return prev.text !in lang.keywords || prev.text in JAVA_MODIFIERS || prev.text in JAVA_PRIMITIVES
        }
        return prev.text in setOf("{", "}", ";", ">", "]", ")")
    }

    private fun jsMethodContext(toks: List<Tok>, i: Int): Boolean {
        val prev = toks.getOrNull(i - 1) ?: return false
        return prev.text in setOf("{", "}", ";", "*") || (prev.kind == TokKind.IDENT && prev.text in JS_MODIFIERS)
    }

    /** After `const name`: is the value a function expression or an arrow function? (`= function`, `= async ...`, `= (...) =>`, `= x =>`). */
    private fun jsIsFunctionValue(toks: List<Tok>, eqIndex: Int): Boolean {
        if (toks.getOrNull(eqIndex)?.text != "=") return false
        var j = eqIndex + 1
        if (toks.getOrNull(j)?.text == "function") return true
        if (toks.getOrNull(j)?.text == "async") j++
        if (toks.getOrNull(j)?.text == "function") return true
        if (toks.getOrNull(j)?.text == "(") {
            var depth = 0
            while (j < toks.size) {
                if (toks[j].text == "(") depth++ else if (toks[j].text == ")") depth--
                j++
                if (depth == 0) break
            }
            // optional TS return type `: T` before the arrow
            var guard = 0
            while (j < toks.size && toks[j].text != "=" && toks[j].text != "{" && toks[j].text != ";" && guard++ < 12) j++
        } else if (toks.getOrNull(j)?.kind == TokKind.IDENT) {
            j++
        } else {
            return false
        }
        return toks.getOrNull(j)?.text == "=" && toks.getOrNull(j + 1)?.text == ">"
    }

    // --------------------------------------------------------------------- Python

    private class PyScope(val indent: Int, val isClass: Boolean)

    private fun python(toks: List<Tok>, content: String, lang: Lang, defs: MutableList<Definition>, declared: MutableSet<Int>) {
        val lines = content.split("\n")
        fun indentOf(line: Int): Int {
            val text = lines.getOrNull(line - 1) ?: return 0
            var n = 0
            while (n < text.length && (text[n] == ' ' || text[n] == '\t')) n++
            return n
        }

        val stack = ArrayList<PyScope>()
        var depth = 0
        var lineStart = true
        var i = 0
        while (i < toks.size) {
            val t = toks[i]
            if (t.kind == TokKind.NEWLINE) {
                if (depth == 0) lineStart = true
                i++
                continue
            }
            if (lineStart) {
                lineStart = false
                val indent = indentOf(t.line)
                while (stack.isNotEmpty() && indent <= stack.last().indent) stack.removeAt(stack.size - 1)
                var keywordIndex = i
                if (t.kind == TokKind.IDENT && t.text == "async" && toks.getOrNull(i + 1)?.text == "def") keywordIndex = i + 1
                val keyword = toks[keywordIndex]
                if (keyword.kind == TokKind.IDENT && (keyword.text == "def" || keyword.text == "class")) {
                    val name = toks.getOrNull(keywordIndex + 1)
                    if (name != null && name.kind == TokKind.IDENT) {
                        val insideFunction = stack.any { !it.isClass }
                        if (!insideFunction) {
                            val kind = if (keyword.text == "class") "class" else if (stack.isNotEmpty()) "method" else "def"
                            defs.add(Definition(name.text, kind, name.line))
                            declared.add(keywordIndex + 1)
                        }
                        stack.add(PyScope(indent, keyword.text == "class"))
                    }
                }
            }
            if (t.kind == TokKind.PUNCT) {
                when (t.text) {
                    "(", "[", "{" -> depth++
                    ")", "]", "}" -> if (depth > 0) depth--
                }
            }
            i++
        }
    }
}
