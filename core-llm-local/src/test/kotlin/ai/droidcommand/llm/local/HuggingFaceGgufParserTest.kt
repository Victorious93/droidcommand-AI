package ai.droidcommand.llm.local

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HuggingFaceGgufParserTest {
    private val searchJson = """
        [
          {
            "id": "HuggingFaceTB/SmolLM2-360M-Instruct-GGUF",
            "likes": 58,
            "private": false,
            "downloads": 248200,
            "gated": false,
            "tags": ["gguf", "license:apache-2.0", "conversational"]
          },
          {
            "id": "secret/paid-gguf",
            "likes": 1,
            "private": true,
            "downloads": 9,
            "tags": ["gguf", "license:apache-2.0"]
          },
          {
            "id": "google/gemma-2b-gguf",
            "likes": 10,
            "private": false,
            "downloads": 50,
            "tags": ["gguf", "license:gemma"]
          }
        ]
    """.trimIndent()

    private val treeJson = """
        [
          {"path": "README.md", "size": 1800},
          {
            "path": "qwen2.5-0.5b-instruct-q4_k_m.gguf",
            "size": 491400032,
            "lfs": {"oid": "sha256:74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db", "size": 491400032}
          },
          {
            "path": "broken.gguf",
            "size": 0
          }
        ]
    """.trimIndent()

    @Test
    fun `search parser keeps free public gguf repos and drops gated private or non-free`() {
        val hits = HuggingFaceGgufParser.parseSearch(searchJson)
        assertEquals(3, hits.size)
        val kept = hits.filter(HuggingFaceGgufParser::searchable)
        assertEquals(listOf("HuggingFaceTB/SmolLM2-360M-Instruct-GGUF"), kept.map { it.id })
        assertEquals("apache-2.0", kept.single().license)
        assertTrue(kept.single().isFreeLicense)
    }

    @Test
    fun `tree parser emits gguf files with size and lfs sha256`() {
        val files = HuggingFaceGgufParser.parseTree(
            treeJson,
            repoId = "Qwen/Qwen2.5-0.5B-Instruct-GGUF",
            revision = "9217f5db79a29953eb74d5343926648285ec7e67",
            license = "apache-2.0",
        )
        assertEquals(1, files.size)
        val f = files.single()
        assertEquals("qwen2.5-0.5b-instruct-q4_k_m.gguf", f.fileName)
        assertEquals(491_400_032L, f.sizeBytes)
        assertEquals("74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db", f.sha256)
        assertEquals("Q4_K_M", f.quant)
        assertTrue(f.downloadUrl.contains("/resolve/9217f5db79a29953eb74d5343926648285ec7e67/"))
        val meta = f.toMetadata("qwen-q4", "Qwen Q4", 4096)
        assertEquals(null, ModelDownloadPolicy.problem(meta!!))
    }

    @Test
    fun `revision parser requires a 40-hex sha`() {
        assertEquals(
            "593b5a2e04c8f3e4ee880263f93e0bd2901ad47f",
            HuggingFaceGgufParser.parseRevision("""{"sha":"593b5a2e04c8f3e4ee880263f93e0bd2901ad47f","cardData":{"license":"apache-2.0"}}"""),
        )
        assertNull(HuggingFaceGgufParser.parseRevision("""{"sha":"main"}"""))
        assertEquals("apache-2.0", HuggingFaceGgufParser.parseLicense("""{"cardData":{"license":"apache-2.0"}}"""))
    }

    @Test
    fun `client search and list go through the injected transport and refuse non-free repos`() {
        val http = HuggingFaceHttp { url, _ ->
            when {
                url.contains("/api/models?") -> HuggingFaceHttpResponse(200, searchJson)
                url.contains("/api/models/HuggingFaceTB/") && !url.contains("/tree/") ->
                    HuggingFaceHttpResponse(200, """{"sha":"593b5a2e04c8f3e4ee880263f93e0bd2901ad47f","cardData":{"license":"apache-2.0"}}""")
                url.contains("/tree/") -> HuggingFaceHttpResponse(200, treeJson)
                else -> HuggingFaceHttpResponse(404, "no")
            }
        }
        val client = HuggingFaceGgufClient(http)
        val repos = assertIs<HuggingFaceSearchResult.Repos>(client.search("smollm2"))
        assertEquals(1, repos.hits.size)
        val files = assertIs<HuggingFaceFilesResult.Files>(client.listGgufFiles("HuggingFaceTB/SmolLM2-360M-Instruct-GGUF"))
        assertEquals(1, files.files.size)
        assertIs<HuggingFaceSearchResult.Failed>(client.search("   "))
        val gemma = HuggingFaceGgufClient(
            HuggingFaceHttp { _, _ ->
                HuggingFaceHttpResponse(200, """{"sha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","cardData":{"license":"gemma"}}""")
            },
        )
        val refused = assertIs<HuggingFaceFilesResult.Failed>(gemma.listGgufFiles("google/gemma-gguf"))
        assertTrue(refused.reason.contains("free license"))
    }

    @Test
    fun `malformed JSON is an empty parse, not a throw`() {
        assertEquals(emptyList(), HuggingFaceGgufParser.parseSearch("not-json"))
        assertEquals(emptyList(), HuggingFaceGgufParser.parseTree("[]", "a/b", "main", null))
    }

    @Test
    fun `only OSI-style free licenses are downloadable`() {
        for (tag in listOf("apache-2.0", "mit", "bsd-3-clause", "cc-by-4.0", "unlicense")) {
            assertTrue(HuggingFaceLicense.isFree(tag), tag)
        }
        for (tag in listOf("llama3", "gemma", "other", "unknown", null, "", "  ")) {
            assertTrue(!HuggingFaceLicense.isFree(tag), tag.toString())
        }
    }
}
