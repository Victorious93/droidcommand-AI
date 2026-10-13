package ai.droidcommand.notes

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

private const val MAX_PATH = 240
private const val MAX_MARKDOWN = 256_000

/** Relative note path: no absolute path, no `.` or `..`, must end in `.md`, no hidden segment. */
fun normalizeNotePath(raw: String): String {
    val relative = normalizeRelative(raw)
    require(relative.endsWith(".md")) { "Notes are markdown files and must end in .md." }
    require(!relative.endsWith("/.md")) { "A note needs a file name." }
    return relative
}

/** Relative folder path. Empty means the vault root. Hidden segments (a leading dot) are refused. */
fun normalizeFolderPath(raw: String): String {
    val trimmed = raw.trim().replace('\\', '/').trim('/')
    if (trimmed.isEmpty()) return ""
    return normalizeRelative(trimmed)
}

/** Turns a typed file name into `name.md`. Slashes are rejected; the folder is added by the caller. */
fun noteFileName(raw: String): String {
    val trimmed = raw.trim()
    require(trimmed.isNotEmpty()) { "A note needs a name." }
    require('/' !in trimmed && '\\' !in trimmed) { "Put the folder separately from the file name." }
    val base = if (trimmed.endsWith(".md", ignoreCase = true)) trimmed.dropLast(3).trim() else trimmed
    require(base.isNotEmpty() && base != "." && base != "..") { "A note needs a name." }
    require(!base.startsWith(".")) { "Hidden notes are not used." }
    return "$base.md"
}

/** Java 7 file APIs, so a minSdk 28 APK does not call `Files.readString` (API 33). */
internal fun readUtf8(path: Path): String = String(Files.readAllBytes(path), StandardCharsets.UTF_8)

internal fun writeUtf8(path: Path, text: String) {
    Files.write(path, text.toByteArray(StandardCharsets.UTF_8))
}

internal fun requireMarkdownSize(markdown: String) {
    require(markdown.length <= MAX_MARKDOWN) { "Note is too large (max $MAX_MARKDOWN characters)." }
}

private fun normalizeRelative(raw: String): String {
    val slashes = raw.trim().replace('\\', '/')
    require(!slashes.startsWith("/") && !slashes.contains(':')) { "Path must stay inside the vault." }
    val trimmed = slashes.trim('/')
    require(trimmed.isNotEmpty()) { "Path is empty." }
    require(trimmed.length <= MAX_PATH) { "Path is too long." }
    val parts = trimmed.split('/')
    require(parts.none { it.isBlank() || it == "." || it == ".." || it.startsWith(".") }) {
        "Path must stay inside the vault and cannot use hidden or parent segments."
    }
    return parts.joinToString("/")
}
