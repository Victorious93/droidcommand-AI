package ai.droidcommand.fleet.worker

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

internal fun ustar(entries: List<Triple<String, ByteArray, Char>>): ByteArray {
    val out = ByteArrayOutputStream()
    for ((name, data, type) in entries) {
        val h = ByteArray(512)
        name.toByteArray().copyInto(h, 0)
        String.format("%07o", 420).toByteArray().copyInto(h, 100) // mode
        String.format("%011o", data.size).toByteArray().copyInto(h, 124) // size
        h[156] = type.code.toByte()
        "ustar".toByteArray().copyInto(h, 257)
        out.write(h)
        out.write(data)
        out.write(ByteArray(((data.size + 511) / 512 * 512) - data.size))
    }
    out.write(ByteArray(1024))
    return out.toByteArray()
}

class SafeTarTest {
    private fun dir() = createTempDirectory("tar").toFile()

    @Test fun `extracts regular files including nested paths`() {
        val d = dir()
        val files = SafeTar.extract(ustar(listOf(Triple("a/b.txt", "hello".toByteArray(), '0'), Triple("c.bin", ByteArray(700) { 7 }, '0'))), d)
        assertEquals(2, files.size)
        assertEquals("hello", File(d, "a/b.txt").readText())
        assertEquals(700, File(d, "c.bin").length().toInt())
    }

    @Test fun `refuses traversal absolute paths links and devices`() {
        for ((name, type) in listOf("../x" to '0', "a/../../x" to '0', "/etc/passwd" to '0', "link" to '2', "sym" to '1', "dev" to '3', "dir" to '5')) {
            val d = dir()
            assertFailsWith<IOException>(name) { SafeTar.extract(ustar(listOf(Triple(name, "x".toByteArray(), type))), d) }
            assertTrue(d.walkTopDown().none { it.isFile }, "nothing may be written for '$name'")
        }
    }

    @Test fun `refuses truncated archive`() {
        val t = ustar(listOf(Triple("a", ByteArray(2000), '0')))
        assertFailsWith<IOException> { SafeTar.extract(t.copyOf(700), dir()) }
    }
}
