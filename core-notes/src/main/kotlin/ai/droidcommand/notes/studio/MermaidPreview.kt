package ai.droidcommand.notes.studio

/**
 * Turns markdown into one self-contained HTML page for an offline WebView.
 * ```mermaid fences become `<div class="mermaid">` blocks that the bundled
 * mermaid.js renders. Everything else is escaped, so note text can never
 * inject script. The page's CSP forbids every network fetch.
 */
object MermaidPreview {
    const val SCRIPT_URL = "file:///android_asset/mermaid/mermaid.min.js"

    sealed class Part {
        data class Markdown(val text: String) : Part()
        data class Diagram(val source: String) : Part()
    }

    fun split(markdown: String): List<Part> {
        val parts = mutableListOf<Part>()
        val buf = StringBuilder()
        val diagram = StringBuilder()
        var inMermaid = false
        var inOtherFence = false
        markdown.replace("\r\n", "\n").lines().forEach { line ->
            val t = line.trim()
            when {
                !inOtherFence && !inMermaid && t.startsWith("```mermaid") -> {
                    if (buf.isNotEmpty()) parts += Part.Markdown(buf.toString().trimEnd('\n'))
                    buf.clear()
                    inMermaid = true
                }
                inMermaid && t == "```" -> {
                    parts += Part.Diagram(diagram.toString().trimEnd('\n'))
                    diagram.clear()
                    inMermaid = false
                }
                inMermaid -> diagram.append(line).append('\n')
                else -> {
                    if (t.startsWith("```")) inOtherFence = !inOtherFence
                    buf.append(line).append('\n')
                }
            }
        }
        if (inMermaid) buf.append("```mermaid\n").append(diagram)
        if (buf.isNotEmpty()) parts += Part.Markdown(buf.toString().trimEnd('\n'))
        return parts
    }

    fun hasDiagram(markdown: String): Boolean = split(markdown).any { it is Part.Diagram }

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

    /** Small markdown to HTML: headings, lists, quotes, fences, inline code/bold/italic. Escaped first. */
    fun markdownToHtml(md: String): String {
        val out = StringBuilder()
        var inCode = false
        var inList = false
        md.lines().forEach { raw ->
            if (raw.trim().startsWith("```")) {
                if (inList) out.append("</ul>").also { inList = false }
                out.append(if (inCode) "</code></pre>" else "<pre><code>")
                inCode = !inCode
                return@forEach
            }
            if (inCode) {
                out.append(escape(raw)).append('\n')
                return@forEach
            }
            val line = inline(escape(raw))
            val bullet = Regex("^\\s*[-*] (.*)").find(line)
            if (bullet != null) {
                if (!inList) out.append("<ul>").also { inList = true }
                out.append("<li>").append(bullet.groupValues[1]).append("</li>")
                return@forEach
            }
            if (inList) out.append("</ul>").also { inList = false }
            val h = Regex("^(#{1,6}) (.*)").find(line)
            when {
                h != null -> {
                    val n = h.groupValues[1].length
                    out.append("<h$n>").append(h.groupValues[2]).append("</h$n>")
                }
                line.startsWith("&gt; ") -> out.append("<blockquote>").append(line.removePrefix("&gt; ")).append("</blockquote>")
                line.isBlank() -> Unit
                else -> out.append("<p>").append(line).append("</p>")
            }
        }
        if (inList) out.append("</ul>")
        if (inCode) out.append("</code></pre>")
        return out.toString()
    }

    private fun inline(s: String): String = s
        .replace(Regex("`([^`]+)`"), "<code>$1</code>")
        .replace(Regex("\\*\\*([^*]+)\\*\\*"), "<strong>$1</strong>")
        .replace(Regex("(?<![\\w])_([^_]+)_(?![\\w])"), "<em>$1</em>")

    fun page(markdown: String, dark: Boolean = true, scriptUrl: String = SCRIPT_URL, scriptSource: String = "file:"): String {
        val body = split(markdown).joinToString("\n") { part ->
            when (part) {
                is Part.Markdown -> markdownToHtml(part.text)
                is Part.Diagram -> "<div class=\"mermaid\">${escape(part.source)}</div>"
            }
        }
        val bg = if (dark) "#0B1220" else "#FFFFFF"
        val fg = if (dark) "#E6EDF7" else "#111111"
        val theme = if (dark) "dark" else "default"
        return """<!DOCTYPE html><html><head><meta charset="utf-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src $scriptSource 'unsafe-inline'; style-src 'unsafe-inline'; img-src data:;">
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>body{background:$bg;color:$fg;font-family:sans-serif;padding:8px;word-wrap:break-word}pre{background:rgba(127,127,127,.15);padding:8px;overflow:auto}.mermaid{background:transparent;margin:8px 0}</style>
</head><body>$body
<script src="$scriptUrl"></script>
<script>if(window.mermaid){mermaid.initialize({startOnLoad:true,securityLevel:'strict',theme:'$theme'});}</script>
</body></html>"""
    }
}
