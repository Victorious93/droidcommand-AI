package ai.droidcommand.app.localmodels

import ai.droidcommand.agent.InMemoryConversationStore
import ai.droidcommand.config.InMemorySecretsVault
import ai.droidcommand.llm.factory.ChatResult
import ai.droidcommand.llm.factory.ChatSession
import ai.droidcommand.llm.local.ModelCheck
import ai.droidcommand.remote.HttpRequestSpec
import ai.droidcommand.remote.HttpResponseSpec
import ai.droidcommand.remote.HttpTransport
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The storage half of Models → Chat, through the real [LocalModelRuntime]: import, restart, removal, and what a chat
 * turn says when the engine is missing. No llama.cpp here: on a JVM the native library is absent, which is exactly the
 * "missing native library" case, so that path is exercised for real rather than faked.
 */
class LocalModelRuntimeFlowTest {
    private val dirs = mutableListOf<File>()

    private fun newDir(): File = Files.createTempDirectory("runtime-flow").toFile().also { dirs += it }

    @AfterTest
    fun cleanUp() = dirs.forEach { it.deleteRecursively() }

    private val noNetwork = object : HttpTransport {
        override fun send(request: HttpRequestSpec): HttpResponseSpec = error("an on-device turn used the network: ${request.url}")
    }

    @Test
    fun `an imported model is listed, verifiable and still there after a restart`() {
        val dir = newDir()
        val bytes = ggufFixture()
        val imported = assertIs<ImportOutcome.Imported>(
            LocalModelRuntime(dir).importModel("My Model.Q4_K_M.gguf", ByteArrayInputStream(bytes), bytes.size.toLong()) {},
        ).model

        val restarted = LocalModelRuntime(dir) // a new process: nothing in memory
        assertEquals(listOf(imported.id), restarted.list().map { it.id })
        assertIs<ModelCheck.Verified>(restarted.repository.verify(imported.id))
    }

    @Test
    fun `an import the process died in the middle of leaves no file and no model after the restart`() {
        val dir = newDir()
        dir.mkdirs()
        File(dir, "import-8843.part").writeBytes(ByteArray(4096))
        val restarted = LocalModelRuntime(dir)
        assertEquals(emptyList(), restarted.list())
        assertEquals(emptyList(), dir.listFiles().orEmpty().map { it.name })
    }

    @Test
    fun `a model file changed after import is refused, not loaded`() {
        val dir = newDir()
        val bytes = ggufFixture()
        val runtime = LocalModelRuntime(dir)
        val model = assertIs<ImportOutcome.Imported>(runtime.importModel("m.gguf", ByteArrayInputStream(bytes), null) {}).model
        val file = File(dir, model.metadata.fileName)
        file.writeBytes(bytes.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() })
        assertIs<ModelCheck.Mismatch>(runtime.repository.verify(model.id))
    }

    @Test
    fun `without the native engine a chat turn fails with what the device reported, and saves nothing`() {
        val dir = newDir()
        val bytes = ggufFixture()
        val runtime = LocalModelRuntime(dir, deviceAbis = { listOf("armeabi-v7a") })
        val model = assertIs<ImportOutcome.Imported>(runtime.importModel("m.gguf", ByteArrayInputStream(bytes), null) {}).model
        val store = InMemoryConversationStore()
        val chat = ChatSession(InMemorySecretsVault(), store, transport = noNetwork, local = runtime.resources)

        val failure = assertIs<ChatResult.Failure>(chat.sendLocal(model.id, "hello"))

        assertTrue(failure.message.contains("processor type (armeabi-v7a)"), failure.message)
        assertTrue(failure.message.contains("arm64-v8a, x86_64"), failure.message)
        assertEquals(null, store.load(ChatSession.DEFAULT_CONVERSATION_ID))
        assertEquals(NativeLibraryAdvice.message(listOf("armeabi-v7a")), runtime.nativeLibraryProblem())
    }

    @Test
    fun `removing a model deletes its copy and it is gone after a restart`() {
        val dir = newDir()
        val bytes = ggufFixture()
        val runtime = LocalModelRuntime(dir)
        val model = assertIs<ImportOutcome.Imported>(runtime.importModel("m.gguf", ByteArrayInputStream(bytes), null) {}).model
        runtime.remove(model.id)
        assertEquals(emptyList(), runtime.list())
        assertEquals(false, File(dir, model.metadata.fileName).exists())
        assertEquals(emptyList(), LocalModelRuntime(dir).list())
    }
}
