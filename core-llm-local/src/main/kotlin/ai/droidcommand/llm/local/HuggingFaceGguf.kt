package ai.droidcommand.llm.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** One Hub model search hit. [id] is `owner/name`. */
data class HuggingFaceRepoHit(
    val id: String,
    val downloads: Long,
    val likes: Int,
    val tags: List<String>,
    val gated: Boolean,
    val privateRepo: Boolean,
) {
    val license: String? = tags.firstOrNull { it.startsWith("license:") }?.removePrefix("license:")

    val isFreeLicense: Boolean get() = HuggingFaceLicense.isFree(license)
}

/** One `.gguf` file in a Hub repo at a pinned commit. */
data class HuggingFaceGgufFile(
    val repoId: String,
    val revision: String,
    val path: String,
    val sizeBytes: Long,
    val sha256: String?,
    val license: String?,
) {
    val fileName: String get() = path.substringAfterLast('/')
    val quant: String? get() = GgufQuant.fromFileName(fileName)
    val downloadUrl: String
        get() = "https://huggingface.co/$repoId/resolve/$revision/$path"

    fun toMetadata(id: String, displayName: String, contextTokens: Int): ModelMetadata? {
        val digest = sha256 ?: return null
        if (sizeBytes <= 0L) return null
        if (!GgufFileName.looksLikeGguf(fileName)) return null
        return ModelMetadata(
            id = id,
            displayName = displayName,
            fileName = fileName,
            sha256 = digest,
            contextTokens = contextTokens,
            sizeBytes = sizeBytes,
            url = downloadUrl,
        )
    }
}

/**
 * Licenses this catalog will download without an API key. Gated, private, Llama/Gemma and
 * other non-free Hub tags are refused: the files stay on Hugging Face, this app does not fetch them.
 */
object HuggingFaceLicense {
    val FREE = setOf(
        "apache-2.0",
        "mit",
        "bsd-2-clause",
        "bsd-3-clause",
        "bsd-3-clause-clear",
        "isc",
        "unlicense",
        "cc0-1.0",
        "cc-by-4.0",
        "mpl-2.0",
        "zlib",
        "artistic-2.0",
    )

    fun isFree(license: String?): Boolean {
        val tag = license?.trim()?.lowercase() ?: return false
        return tag in FREE
    }
}

/**
 * Parses Hugging Face Hub JSON. No network: callers fetch the body (tests pass fixtures).
 *
 * Shapes, as returned by Hub on 2026-10-30:
 * - `GET /api/models?search=&filter=gguf` → JSON array of objects with `id`/`modelId`, `downloads`,
 *   `likes`, `tags`, optional `gated`/`private`.
 * - `GET /api/models/{id}` → object with `sha` (40-hex) and `cardData.license`.
 * - `GET /api/models/{id}/tree/{sha}` → JSON array of `{path, size, lfs:{oid,size}}`.
 */
object HuggingFaceGgufParser {
    private val json = Json { ignoreUnknownKeys = true }
    private val SHA256 = Regex("^[0-9a-f]{64}$")
    private val COMMIT = Regex("^[0-9a-f]{40}$")

    fun parseSearch(body: String): List<HuggingFaceRepoHit> {
        val root = parse(body) ?: return emptyList()
        val array = root as? JsonArray ?: return emptyList()
        return array.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val id = string(obj, "id") ?: string(obj, "modelId") ?: return@mapNotNull null
            if (id.isBlank() || '/' !in id) return@mapNotNull null
            HuggingFaceRepoHit(
                id = id,
                downloads = long(obj, "downloads") ?: 0L,
                likes = int(obj, "likes") ?: 0,
                tags = stringList(obj, "tags"),
                gated = boolean(obj, "gated"),
                privateRepo = boolean(obj, "private"),
            )
        }
    }

    /** Hub commit for the repo, or null when the body has no 40-hex `sha`. */
    fun parseRevision(body: String): String? {
        val obj = parse(body) as? JsonObject ?: return null
        val sha = string(obj, "sha") ?: return null
        return sha.takeIf { COMMIT.matches(it) }
    }

    fun parseLicense(body: String): String? {
        val obj = parse(body) as? JsonObject ?: return null
        val card = obj["cardData"] as? JsonObject
        val fromCard = card?.let { string(it, "license") }
        if (!fromCard.isNullOrBlank()) return fromCard
        val tags = stringList(obj, "tags")
        return tags.firstOrNull { it.startsWith("license:") }?.removePrefix("license:")
    }

    fun parseTree(body: String, repoId: String, revision: String, license: String?): List<HuggingFaceGgufFile> {
        if (!COMMIT.matches(revision)) return emptyList()
        val root = parse(body) ?: return emptyList()
        val array = root as? JsonArray ?: return emptyList()
        return array.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val path = string(obj, "path") ?: return@mapNotNull null
            if (!GgufFileName.looksLikeGguf(path)) return@mapNotNull null
            val lfs = obj["lfs"] as? JsonObject
            val size = long(obj, "size") ?: lfs?.let { long(it, "size") } ?: return@mapNotNull null
            if (size <= 0L) return@mapNotNull null
            val oid = lfs?.let { string(it, "oid") }
            val sha256 = oid?.removePrefix("sha256:")?.lowercase()?.takeIf { SHA256.matches(it) }
            HuggingFaceGgufFile(
                repoId = repoId,
                revision = revision,
                path = path,
                sizeBytes = size,
                sha256 = sha256,
                license = license,
            )
        }
    }

    /** Repos this app will list: public, not gated, tagged gguf, free license. */
    fun searchable(hit: HuggingFaceRepoHit): Boolean {
        if (hit.privateRepo || hit.gated) return false
        if (!hit.isFreeLicense) return false
        return hit.tags.any { it.equals("gguf", ignoreCase = true) } ||
            hit.id.contains("gguf", ignoreCase = true)
    }

    private fun parse(body: String): JsonElement? = runCatching { json.parseToJsonElement(body) }.getOrNull()

    private fun string(obj: JsonObject, key: String): String? =
        (obj[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun long(obj: JsonObject, key: String): Long? {
        val p = obj[key] as? JsonPrimitive ?: return null
        return p.longOrNull ?: p.contentOrNull?.toLongOrNull()
    }

    private fun int(obj: JsonObject, key: String): Int? {
        val p = obj[key] as? JsonPrimitive ?: return null
        return p.intOrNull ?: p.contentOrNull?.toIntOrNull()
    }

    private fun boolean(obj: JsonObject, key: String): Boolean {
        val el = obj[key] ?: return false
        val p = el as? JsonPrimitive ?: return false
        return p.booleanOrNull == true || p.contentOrNull.equals("true", ignoreCase = true)
    }

    private fun stringList(obj: JsonObject, key: String): List<String> {
        val el = obj[key] ?: return emptyList()
        val array = el as? JsonArray ?: return emptyList()
        return array.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    }
}

/** HTTP seam so tests never touch huggingface.co. */
fun interface HuggingFaceHttp {
    fun get(url: String, headers: Map<String, String>): HuggingFaceHttpResponse
}

data class HuggingFaceHttpResponse(val status: Int, val body: String)

/**
 * Hub client: search GGUF repos and list `.gguf` files at a pinned commit. https only, huggingface.co
 * only, no credentials unless [token] is supplied (an optional user token from the secrets vault).
 */
class HuggingFaceGgufClient(
    private val http: HuggingFaceHttp,
    private val token: () -> String? = { null },
) {
    fun search(query: String, limit: Int = DEFAULT_LIMIT): HuggingFaceSearchResult {
        val q = query.trim()
        if (q.isEmpty()) return HuggingFaceSearchResult.Failed("Type a search term.")
        if (q.length > MAX_QUERY_CHARS) return HuggingFaceSearchResult.Failed("Search is too long.")
        val encoded = java.net.URLEncoder.encode(q, Charsets.UTF_8.name())
        val capped = limit.coerceIn(1, MAX_LIMIT)
        val url = "$API/models?search=$encoded&filter=gguf&sort=downloads&direction=-1&limit=$capped"
        return when (val response = request(url)) {
            is HubGet.Failed -> HuggingFaceSearchResult.Failed(response.reason)
            is HubGet.Ok -> {
                val hits = HuggingFaceGgufParser.parseSearch(response.body).filter(HuggingFaceGgufParser::searchable)
                HuggingFaceSearchResult.Repos(hits)
            }
        }
    }

    fun listGgufFiles(repoId: String): HuggingFaceFilesResult {
        val id = repoId.trim()
        if (!REPO_ID.matches(id)) return HuggingFaceFilesResult.Failed("Not a Hugging Face repo id.")
        val encoded = id.split('/').joinToString("/") { java.net.URLEncoder.encode(it, Charsets.UTF_8.name()) }
        val meta = when (val response = request("$API/models/$encoded")) {
            is HubGet.Failed -> return HuggingFaceFilesResult.Failed(response.reason)
            is HubGet.Ok -> response.body
        }
        val revision = HuggingFaceGgufParser.parseRevision(meta)
            ?: return HuggingFaceFilesResult.Failed("Hugging Face did not report a commit for this repo.")
        val license = HuggingFaceGgufParser.parseLicense(meta)
        if (!HuggingFaceLicense.isFree(license)) {
            return HuggingFaceFilesResult.Failed("This repo is not under a free license this app will download (${license ?: "unknown"}).")
        }
        val tree = when (val response = request("$API/models/$encoded/tree/$revision")) {
            is HubGet.Failed -> return HuggingFaceFilesResult.Failed(response.reason)
            is HubGet.Ok -> response.body
        }
        val files = HuggingFaceGgufParser.parseTree(tree, id, revision, license)
        if (files.isEmpty()) return HuggingFaceFilesResult.Failed("No .gguf files in this repo.")
        return HuggingFaceFilesResult.Files(files)
    }

    private fun request(url: String): HubGet {
        if (!url.startsWith("https://huggingface.co/")) return HubGet.Failed("Refusing a non-Hub URL.")
        val headers = buildMap {
            put("User-Agent", USER_AGENT)
            put("Accept", "application/json")
            token()?.trim()?.takeIf { it.isNotEmpty() }?.let { put("Authorization", "Bearer $it") }
        }
        val response = try {
            http.get(url, headers)
        } catch (e: Exception) {
            return HubGet.Failed("Hugging Face request failed: ${e.message ?: e.javaClass.simpleName}.")
        }
        if (response.status == 401 || response.status == 403) {
            return HubGet.Failed("Hugging Face refused the request (HTTP ${response.status}). A token in Settings may be required for this repo.")
        }
        if (response.status !in 200..299) {
            return HubGet.Failed("Hugging Face returned HTTP ${response.status}.")
        }
        return HubGet.Ok(response.body)
    }

    private sealed interface HubGet {
        data class Ok(val body: String) : HubGet
        data class Failed(val reason: String) : HubGet
    }

    companion object {
        const val SECRET_ID = "huggingface.hub.token"
        const val USER_AGENT = "droidcommand-ai-gguf-catalog/1.0"
        private const val API = "https://huggingface.co/api"
        private const val DEFAULT_LIMIT = 20
        private const val MAX_LIMIT = 50
        private const val MAX_QUERY_CHARS = 80
        private val REPO_ID = Regex("^[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?/[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?$")
    }
}

sealed class HuggingFaceSearchResult {
    data class Repos(val hits: List<HuggingFaceRepoHit>) : HuggingFaceSearchResult()
    data class Failed(val reason: String) : HuggingFaceSearchResult()
}

sealed class HuggingFaceFilesResult {
    data class Files(val files: List<HuggingFaceGgufFile>) : HuggingFaceFilesResult()
    data class Failed(val reason: String) : HuggingFaceFilesResult()
}

/**
 * Real Hub GET over `HttpURLConnection`. https only; redirects stay on https (the Hub API does not
 * need a CDN hop). Timeouts match [HttpsModelDownloader]'s connect/read values.
 */
class HttpsHuggingFaceHttp : HuggingFaceHttp {
    override fun get(url: String, headers: Map<String, String>): HuggingFaceHttpResponse {
        require(url.startsWith("https://")) { "https only" }
        val conn = java.net.URI(url).toURL().openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        return try {
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            HuggingFaceHttpResponse(status, body)
        } finally {
            conn.disconnect()
        }
    }
}
