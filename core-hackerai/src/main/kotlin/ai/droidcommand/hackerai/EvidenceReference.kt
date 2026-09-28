package ai.droidcommand.hackerai

// Ported from hackeraiETC/lib/ai/subagents/evidence-references.ts

private val FILE_PREFIX = "file:"
private val UNC_PATTERN = Regex("""^//""")
private val URL_PATTERN = Regex("""^https?://""")
private val LINE_SUFFIX_PATTERN = Regex(""":\d+(-\d+)?$""")

/**
 * Resolves a raw evidence reference string to a file path, or returns null
 * if the reference is not a file reference (URL, UNC path, or unrecognised format).
 *
 * Mirrors the TypeScript `evidenceFilePath` implementation exactly:
 * - Strips the "file:" prefix
 * - Strips trailing line-number suffixes (:42 or :10-20)
 * - Rejects UNC paths (//...)
 * - Rejects http/https URLs
 */
fun evidenceFilePath(ref: String): String? {
    val path = if (ref.startsWith(FILE_PREFIX)) ref.removePrefix(FILE_PREFIX) else ref
    if (UNC_PATTERN.containsMatchIn(path)) return null
    if (URL_PATTERN.containsMatchIn(path)) return null
    return LINE_SUFFIX_PATTERN.replace(path, "").trimEnd()
}
