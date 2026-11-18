package ai.droidcommand.fleet.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** One file attached to a GitHub release. [sha256] is GitHub's own `digest` field when present (lowercase hex). */
data class ReleaseAsset(val name: String, val url: String, val sizeBytes: Long, val sha256: String?)

data class FleetRelease(
    val tag: String,
    val draft: Boolean,
    val prerelease: Boolean,
    val assets: List<ReleaseAsset>,
)

/** Reads the JSON of `GET /repos/{owner}/{name}/releases` (an array) or `.../releases/latest` (one object). */
object GitHubReleases {
    private val json = Json { ignoreUnknownKeys = true }

    fun releasesUrl(repo: String): String {
        require(REPO.matches(repo)) { "repo must look like owner/name" }
        return "https://api.github.com/repos/$repo/releases?per_page=10"
    }

    /** Broken entries are skipped; unusable input gives an empty list, never a guess. */
    fun parse(text: String?): List<FleetRelease> {
        if (text.isNullOrBlank()) return emptyList()
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return emptyList()
        val objects = when (root) {
            is JsonArray -> root.mapNotNull { it as? JsonObject }
            is JsonObject -> listOf(root)
            else -> emptyList()
        }
        return objects.mapNotNull { runCatching { release(it) }.getOrNull() }
    }

    private fun release(o: JsonObject): FleetRelease? {
        val tag = o["tag_name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val assets = o["assets"]?.jsonArray.orEmpty().mapNotNull { element ->
            runCatching {
                val a = element.jsonObject
                val name = a["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@runCatching null
                val url = a["browser_download_url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: return@runCatching null
                val digest = a["digest"]?.jsonPrimitive?.contentOrNull?.removePrefix("sha256:")?.lowercase()
                    ?.takeIf { SHA256.matches(it) }
                ReleaseAsset(name, url, a["size"]?.jsonPrimitive?.longOrNull ?: -1L, digest)
            }.getOrNull()
        }
        return FleetRelease(
            tag = tag,
            draft = o["draft"]?.jsonPrimitive?.booleanOrNull ?: false,
            prerelease = o["prerelease"]?.jsonPrimitive?.booleanOrNull ?: false,
            assets = assets,
        )
    }

    private val REPO = Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
    private val SHA256 = Regex("[0-9a-f]{64}")
}
