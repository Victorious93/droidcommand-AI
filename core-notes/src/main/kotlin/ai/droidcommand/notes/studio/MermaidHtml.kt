package ai.droidcommand.notes.studio

/**
 * Builds the HTML the preview WebView loads. Markdown is rendered to simple escaped HTML; ```mermaid blocks become
 * `<pre class="mermaid">` for the bundled mermaid.js, loaded from app assets. Nothing is fetched from the network:
 * the page carries a Content-Security-Policy that allows only the local script and inline styles.
 */
object MermaidHtml {
    const val SCRIPT_URL = "file:///android_asset/mermaid/mermaid.min.js"

    fun escape(s: String): String = buildString(s.length) {
        s.forEach { c ->
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(c)
            }
        }
    }

    /** Mermaid diagram sources in document order. */
    fun diagrams(markdown: String): List<String> = blocks(markdown).filter { it.first == "mermaid" }.map { it.second }

    private fun blocks(markdown: String): List<Pair<String?, String>> {
        val out = mutableListOf<Pair<String?, String>>()
        var lang: String? = null
        var inFence = false
        val buf = StringBuilder()
        markdown.replace("\r\n", "\n").lines().forEach { line ->
            if (line.trimStart().startsWith("```")) {
                if (!inFence) {
                    if (buf.isNotEmpty()) out += null to buf.toString().also { buf.clear() }
                    lang = line.trim().removePrefix("```").trim().lowercase().ifEmpty { "" }
                    inFence = true
                } else {
                    out += lang to buf.toString().trimEnd('\n')
                    buf.clear()
                    inFence = false
                }
            } else {
                buf.append(line).append('\n')
            }
        }
        if (buf.isNotEmpty()) out += (if (inFence) lang else null) to buf.toString()
        return out
    }

    private fun inline(s: String): String = escape(s)
        .replace(Regex("`([^`]+)`"), "<code>$1</code>")
        .replace(Regex("\\*\\*([^*]+)\\*\\*"), "<strong>$1</strong>")
        .replace(Regex("\\*([^*]+)\\*"), "<em>$1</em>")
        .replace(Regex("~~([^~]+)~~"), "<del>$1</del>")

    private fun prose(text: String): String = buildString {
        text.lines().forEach { raw ->
            val line = raw.trimEnd()
            val h = Regex("^(#{1,6}) (.*)").find(line)
            when {
                line.isBlank() -> Unit
                h != null -> {
                    val level = h.groupValues[1].length
                    append("<h$level>").append(inline(h.groupValues[2])).append("</h$level>\n")
                }
                line == "---" -> append("<hr>\n")
                line.startsWith("- [ ] ") -> append("<p>&#9744; ").append(inline(line.drop(6))).append("</p>\n")
                line.startsWith("- [x] ") -> append("<p>&#9745; ").append(inline(line.drop(6))).append("</p>\n")
                line.startsWith("- ") -> append("<p>&bull; ").append(inline(line.drop(2))).append("</p>\n")
                line.startsWith("> ") -> append("<blockquote>").append(inline(line.drop(2))).append("</blockquote>\n")
                else -> append("<p>").append(inline(line)).append("</p>\n")
            }
        }
    }

    fun page(markdown: String, dark: Boolean): String {
        val body = blocks(markdown).joinToString("") { (lang, text) ->
            when (lang) {
                null -> prose(text)
                "mermaid" -> "<pre class=\"mermaid\">${escape(text)}</pre>\n"
                else -> "<pre><code>${escape(text)}</code></pre>\n"
            }
        }
        val theme = if (dark) "dark" else "default"
        val fg = if (dark) "#e6e6e6" else "#1a1a1a"
        val bg = if (dark) "#121212" else "#ffffff"
        val hasDiagram = "class=\"mermaid\"" in body
        val script = if (hasDiagram) {
            "<script src=\"$SCRIPT_URL\"></script>\n<script>mermaid.initialize({startOnLoad:true,theme:'$theme',securityLevel:'strict'});</script>\n"
        } else {
            ""
        }
        return """<!DOCTYPE html>
<html><head><meta charset="utf-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'self' file: 'unsafe-inline'; style-src 'unsafe-inline'; img-src data:">
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>body{font-family:sans-serif;color:$fg;background:$bg;padding:8px;line-height:1.45}pre{overflow-x:auto}code{font-family:monospace}blockquote{border-left:3px solid #888;margin:0;padding-left:8px}</style>
</head><body>
$body$script</body></html>
"""
    }
}
