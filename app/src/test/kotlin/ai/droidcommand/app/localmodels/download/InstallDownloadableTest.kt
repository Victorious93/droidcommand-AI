package ai.droidcommand.app.localmodels.download

import ai.droidcommand.app.localmodels.LocalModelRuntime
import ai.droidcommand.llm.local.ModelCheck
import ai.droidcommand.llm.local.ModelDownloader
import ai.droidcommand.llm.local.ModelInstallResult
import ai.droidcommand.llm.local.ModelMetadata
import ai.droidcommand.llm.local.ModelRepository
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class InstallDownloadableTest {
    private val commit = "0188c9bf409793f810680a5a431e7b899c46104c"
    private val dirs = mutableListOf<File>()

    private fun newDir(): File = Files.createTempDirectory("installable").toFile().also { dirs += it }

    @AfterTest
    fun cleanUp() = dirs.forEach { it.deleteRecursively() }

    private fun gguf(payload: String = "tensors"): ByteArray {
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        header.put("GGUF".toByteArray()).putInt(3).putLong(10).putLong(5)
        return header.array() + payload.toByteArray()
    }

    private fun meta(
        body: ByteArray,
        id: String = "example",
        fileName: String = "example.gguf",
        url: String = "https://huggingface.co/o/r/resolve/$commit/example.gguf",
    ) = ModelMetadata(id, "Example", fileName, ModelRepository.sha256Hex(ByteArrayInputStream(body)), 2048, body.size.toLong(), url)

    private class Serving(private val body: ByteArray) : ModelDownloader {
        var calls = 0

        override fun download(url: String, dest: File, maxBytes: Long, onBytes: (Long) -> Unit, cancelled: () -> Boolean) {
            calls++
            dest.writeBytes(body)
            onBytes(body.size.toLong())
        }
    }

    @Test
    fun `a verified GGUF is installed, listed and survives a restart`() {
        val dir = newDir()
        val body = gguf()
        val model = meta(body)
        val downloader = Serving(body)
        val runtime = LocalModelRuntime(dir)

        assertIs<ModelInstallResult.Installed>(runtime.installDownloadable(model, downloader, {}, { false }))

        assertEquals(listOf("example"), runtime.list().map { it.id })
        assertIs<ModelCheck.Verified>(runtime.repository.verify("example"))
        assertEquals(listOf("example"), LocalModelRuntime(dir).list().map { it.id }, "the registry file persists it")
    }

    @Test
    fun `a download that is not a GGUF is rejected and nothing is registered`() {
        val dir = newDir()
        val html = "<html>403</html>".toByteArray()
        val runtime = LocalModelRuntime(dir)

        val result = runtime.installDownloadable(meta(html), Serving(html), {}, { false })

        assertIs<ModelInstallResult.Failed>(result)
        assertTrue(result.reason.contains("GGUF"))
        assertTrue(runtime.list().isEmpty())
        assertFalse(File(dir, "example.gguf").exists())
        assertFalse(File(dir, "example.gguf.part").exists())
    }

    @Test
    fun `a digest mismatch is rejected and nothing is registered`() {
        val dir = newDir()
        val published = gguf("what the publisher hashed")
        val served = gguf("what arrived instead!!")
        val runtime = LocalModelRuntime(dir)

        val result = runtime.installDownloadable(meta(published), Serving(served.copyOf(published.size)), {}, { false })

        assertIs<ModelInstallResult.Failed>(result)
        assertTrue(runtime.list().isEmpty())
        assertFalse(File(dir, "example.gguf").exists())
    }

    @Test
    fun `an entry the policy rejects never reaches the network`() {
        val body = gguf()
        val downloader = Serving(body)
        val unpinned = meta(body, url = "https://huggingface.co/o/r/resolve/main/example.gguf")

        val result = LocalModelRuntime(newDir()).installDownloadable(unpinned, downloader, {}, { false })

        assertIs<ModelInstallResult.Failed>(result)
        assertEquals(0, downloader.calls)
    }

    @Test
    fun `file names that collide with the registry or import temp files are refused`() {
        val body = gguf()
        for (name in listOf("registry.txt", "import-1.gguf")) {
            val downloader = Serving(body)
            val result = LocalModelRuntime(newDir()).installDownloadable(meta(body, fileName = name), downloader, {}, { false })
            assertIs<ModelInstallResult.Failed>(result, name)
            assertEquals(0, downloader.calls, name)
        }
    }

    @Test
    fun `an installed model is not downloaded again, and a different file cannot reuse its id`() {
        val dir = newDir()
        val body = gguf()
        val runtime = LocalModelRuntime(dir)
        runtime.installDownloadable(meta(body), Serving(body), {}, { false })

        val again = Serving(body)
        assertIs<ModelInstallResult.Installed>(runtime.installDownloadable(meta(body), again, {}, { false }))
        assertEquals(0, again.calls)

        val other = gguf("a different model")
        val clash = runtime.installDownloadable(meta(other), Serving(other), {}, { false })
        assertIs<ModelInstallResult.Failed>(clash)
        assertEquals(1, runtime.list().size)
    }
}
