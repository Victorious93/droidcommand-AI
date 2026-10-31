package ai.droidcommand.llm.local

import java.net.URLDecoder

/**
 * Decides whether a picker display name, content URI path, or filesystem path refers to a `.gguf`
 * file. Android's Storage Access Framework often returns a display name without the extension, or a
 * `content://` last path segment such as `msf:123` / `document:45`, so callers must try every
 * candidate rather than the display name alone.
 */
object GgufFileName {
    const val EXTENSION = ".gguf"

    /** True when [value] itself, a decoded form of it, or its last path segment ends in `.gguf`. */
    fun looksLikeGguf(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        val trimmed = value.trim()
        if (endsWithGguf(trimmed)) return true
        val decoded = decode(trimmed)
        if (decoded != trimmed && endsWithGguf(decoded)) return true
        val segment = lastSegment(decoded)
        return segment != decoded && endsWithGguf(segment)
    }

    /**
     * A label-safe file name ending in `.gguf`, or null when none of [candidates] looks like one.
     * The first matching candidate wins. Control characters are stripped; path separators are not
     * kept (a bare name is required by [ModelMetadata]).
     */
    fun resolve(vararg candidates: String?): String? {
        for (raw in candidates) {
            if (raw.isNullOrBlank()) continue
            val decoded = decode(raw.trim())
            val segment = lastSegment(decoded)
            if (endsWithGguf(segment)) return sanitize(segment)
            if (endsWithGguf(decoded)) return sanitize(lastSegment(decoded))
        }
        return null
    }

    fun displayLabel(fileName: String): String {
        val stem = if (endsWithGguf(fileName)) fileName.dropLast(EXTENSION.length) else fileName
        return stem.map { if (it.isISOControl() || it == '\t') ' ' else it }
            .joinToString("")
            .trim()
            .take(MAX_LABEL_CHARS)
            .ifEmpty { "On-device model" }
    }

    private fun endsWithGguf(value: String): Boolean = value.endsWith(EXTENSION, ignoreCase = true)

    private fun lastSegment(value: String): String {
        val cut = value.replace('\\', '/').substringAfterLast('/')
        return cut.substringAfterLast(':')
    }

    private fun decode(value: String): String =
        try {
            URLDecoder.decode(value.replace('+', ' '), Charsets.UTF_8.name())
        } catch (_: IllegalArgumentException) {
            value
        }

    private fun sanitize(name: String): String {
        val bare = name.replace('\\', '/').substringAfterLast('/')
        val cleaned = buildString(bare.length) {
            for (ch in bare) {
                when {
                    ch.isISOControl() -> Unit
                    ch == '/' || ch == '\\' -> Unit
                    else -> append(ch)
                }
            }
        }.trim()
        return cleaned
    }

    private const val MAX_LABEL_CHARS = 80
}
