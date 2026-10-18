package ai.droidcommand.llm.local

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelDownloadPolicyTest {
    private val commit = "0188c9bf409793f810680a5a431e7b899c46104c"
    private val sha = "a".repeat(64)

    private fun meta(
        url: String? = "https://huggingface.co/owner/repo-GGUF/resolve/$commit/model.Q4_K_M.gguf",
        size: Long? = 1_000L,
        fileName: String = "model.Q4_K_M.gguf",
    ) = ModelMetadata("m", "M", fileName, sha, 2048, size, url)

    @Test
    fun `a pinned https entry with a published size is downloadable`() {
        assertNull(ModelDownloadPolicy.problem(meta()))
        assertTrue(ModelDownloadPolicy.isDownloadable(meta()))
    }

    @Test
    fun `an entry missing a URL or size is not downloadable`() {
        assertEquals("no download URL", ModelDownloadPolicy.problem(meta(url = null)))
        assertEquals("no published size", ModelDownloadPolicy.problem(meta(size = null)))
    }

    @Test
    fun `an unpinned or branch-named revision is refused`() {
        for (revision in listOf("main", "refs%2Fpr%2F1", commit.take(39), commit.uppercase())) {
            val url = "https://huggingface.co/owner/repo-GGUF/resolve/$revision/model.gguf"
            assertNotNull(ModelDownloadPolicy.problem(meta(url = url)), revision)
        }
        assertNotNull(ModelDownloadPolicy.problem(meta(url = "https://example.test/model.gguf")))
    }

    @Test
    fun `non-gguf names are refused`() {
        assertNotNull(ModelDownloadPolicy.problem(meta(fileName = "model.bin")))
    }

    @Test
    fun `the pinned embedding model entry already satisfies the policy`() {
        assertNull(ModelDownloadPolicy.problem(EmbeddingModelCatalog.NOMIC_EMBED_TEXT_V1_5_Q4_K_M.metadata))
    }

    @Test
    fun `the chat catalog ships empty and every entry it ever gets must be downloadable`() {
        assertTrue(ChatModelCatalog.entries.isEmpty(), "no unverified model may ship; see ChatModelCatalog's KDoc")
        ChatModelCatalog.entries.forEach { assertNull(ModelDownloadPolicy.problem(it), it.id) }
    }
}
