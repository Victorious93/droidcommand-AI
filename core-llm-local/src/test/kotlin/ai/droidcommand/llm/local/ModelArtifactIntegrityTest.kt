package ai.droidcommand.llm.local

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ModelArtifactIntegrityTest {
    private val good = ggufBytes()
    private val goodSha = ModelRepository.sha256Hex(ByteArrayInputStream(good))

    private fun file(bytes: ByteArray): File {
        val dir = Files.createTempDirectory("integrity").toFile().also { it.deleteOnExit() }
        return File(dir, "m.gguf").also { it.writeBytes(bytes) }
    }

    private class CountingHasher(private val real: FileHasher = StreamingSha256) : FileHasher {
        var calls = 0

        override fun sha256(file: File): String {
            calls++
            return real.sha256(file)
        }
    }

    private fun failure(r: IntegrityResult): IntegrityFailure = assertIs<IntegrityResult.Invalid>(r).failure

    @Test
    fun `a GGUF file with the right size and digest is valid`() {
        assertEquals(IntegrityResult.Valid, ModelArtifactIntegrity().verify(file(good), good.size.toLong(), goodSha))
    }

    @Test
    fun `a missing file is reported as missing`() {
        val f = File(Files.createTempDirectory("integrity").toFile(), "nope.gguf")
        assertEquals(IntegrityFailure.MISSING_FILE, failure(ModelArtifactIntegrity().verify(f, null, goodSha)))
    }

    @Test
    fun `a wrong size is rejected before the header is read or the file is hashed`() {
        val hasher = CountingHasher()
        val r = ModelArtifactIntegrity(hasher).verify(file(good + 1), good.size.toLong(), goodSha)
        assertEquals(IntegrityFailure.SIZE_MISMATCH, failure(r))
        assertEquals(0, hasher.calls)
    }

    @Test
    fun `a non-GGUF file with matching size and digest is still rejected, without hashing`() {
        val html = "<html>not a model, but its digest is whatever the publisher said</html>".toByteArray()
        val sha = ModelRepository.sha256Hex(ByteArrayInputStream(html))
        val hasher = CountingHasher()
        val r = ModelArtifactIntegrity(hasher).verify(file(html), html.size.toLong(), sha)
        assertEquals(IntegrityFailure.FORMAT_INVALID, failure(r))
        assertEquals(0, hasher.calls)
    }

    @Test
    fun `the header check can be turned off for stand-in bytes`() {
        val bytes = "pretend gguf bytes".toByteArray()
        val sha = ModelRepository.sha256Hex(ByteArrayInputStream(bytes))
        assertEquals(IntegrityResult.Valid, ModelArtifactIntegrity(requireGguf = false).verify(file(bytes), null, sha))
    }

    @Test
    fun `a valid GGUF with the wrong digest reports the digest it actually has`() {
        val tampered = ggufBytes(payload = "tampered!!".toByteArray())
        val r = assertIs<IntegrityResult.Invalid>(ModelArtifactIntegrity().verify(file(tampered), null, goodSha))
        assertEquals(IntegrityFailure.HASH_MISMATCH, r.failure)
        assertEquals(ModelRepository.sha256Hex(ByteArrayInputStream(tampered)), r.actualSha256)
    }

    @Test
    fun `a hashing I-O error is reported, not thrown`() {
        val hasher = FileHasher { throw IOException("disk went away") }
        val r = ModelArtifactIntegrity(hasher).verify(file(good), null, goodSha)
        assertEquals(IntegrityFailure.HASH_UNAVAILABLE, failure(r))
        assertNull(assertIs<IntegrityResult.Invalid>(r).actualSha256)
    }
}
