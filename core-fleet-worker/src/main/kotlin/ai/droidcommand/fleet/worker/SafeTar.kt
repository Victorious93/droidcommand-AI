package ai.droidcommand.fleet.worker

import java.io.File
import java.io.IOException

/**
 * Minimal, strict ustar reader for job inputs. Only regular files are extracted. Absolute paths, `..` segments, links,
 * devices, oversized entries and too many entries are refused, and every output path is re-checked against the
 * destination's canonical path, so a hostile archive cannot write outside the job directory.
 */
object SafeTar {
    const val MAX_ENTRIES = 10_000
    const val MAX_TOTAL_BYTES = 1L shl 30

    fun extract(tar: ByteArray, dest: File): List<File> {
        val root = dest.canonicalFile.also { it.mkdirs() }
        val out = mutableListOf<File>()
        var pos = 0
        var total = 0L
        var entries = 0
        while (pos + 512 <= tar.size) {
            val header = tar.copyOfRange(pos, pos + 512)
            if (header.all { it == 0.toByte() }) break
            if (++entries > MAX_ENTRIES) throw IOException("tar has too many entries")
            val name = cString(header, 0, 100)
            val prefix = cString(header, 345, 155)
            val full = if (prefix.isNotEmpty()) "$prefix/$name" else name
            val size = octal(header, 124, 12)
            val type = header[156].toInt().toChar()
            if (type != '0' && type != '\u0000') throw IOException("tar entry '$full' is not a regular file (type '$type')")
            if (full.isEmpty() || full.startsWith("/") || full.split('/').any { it == ".." } || full.contains('\\') || full.contains('\u0000')) {
                throw IOException("tar entry path refused: '$full'")
            }
            total += size
            if (size < 0 || total > MAX_TOTAL_BYTES) throw IOException("tar entry too large")
            pos += 512
            if (pos + size > tar.size) throw IOException("tar truncated")
            val target = File(root, full).canonicalFile
            if (!target.path.startsWith(root.path + File.separator)) throw IOException("tar entry escapes the job directory: '$full'")
            target.parentFile.mkdirs()
            target.writeBytes(tar.copyOfRange(pos, pos + size.toInt()))
            out += target
            pos += ((size + 511) / 512 * 512).toInt()
        }
        return out
    }

    private fun cString(b: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && b[end] != 0.toByte()) end++
        return String(b, off, end - off, Charsets.UTF_8)
    }

    private fun octal(b: ByteArray, off: Int, len: Int): Long {
        val s = cString(b, off, len).trim()
        return if (s.isEmpty()) 0 else s.toLongOrNull(8) ?: throw IOException("bad tar size field")
    }
}
