package ai.droidcommand.remotepc

/** VictorSuite's cached `reachable` column (§5): 1, 0, or -1 for "not checked this session". */
enum class CachedReachability { REACHABLE, UNREACHABLE, UNKNOWN }

/**
 * One row of `content://com.termux.victor.pc/profiles`. Public data only: the spec has no column for a
 * token, private key, password, agent socket or proxy address, and this class adds none.
 */
data class PcProfile(
    val profileId: String,
    val name: String,
    val user: String,
    /** Preferred connect order (LAN first, then tailnet). Hints only; identity is [hostKeys]. */
    val hosts: List<String>,
    val port: Int,
    val hostKeys: List<String>,
    val caps: Set<String>,
    val pairedAtMs: Long,
    val lastSeenHost: String?,
    val lastSeenAtMs: Long?,
    val reachable: CachedReachability,
    val callerEnrolled: Boolean,
) {
    val supportsSftp: Boolean get() = CAP_SFTP in caps

    companion object {
        const val CAP_SFTP = "sftp"
        const val CAP_ADB = "adb"
    }
}

/** Why a provider row was skipped. Skipped rows are logged by the caller, never shown raw. */
sealed class PcRowResult {
    data class Ok(val profile: PcProfile) : PcRowResult()
    data class Skipped(val reason: String) : PcRowResult()
}

/**
 * Parses provider rows given as column name → value text (the Android adapter reads every column as text,
 * the same way the fleet-theme reader does). Strict on identity fields, lenient on optional ones.
 */
object PcProfileParser {
    // §1 says base32 (A–Z, 2–7), but the spec's own example id `7Q2M4KX9TB5N` has a 9, so any uppercase
    // letter or digit is accepted. Still exactly 12 characters, so nothing else can ride along in the extra.
    private val PROFILE_ID = Regex("^[A-Z0-9]{12}$")
    private val POSIX_USER = Regex("^[a-z_][a-z0-9_-]{0,31}$")
    private const val MAX_HOSTS = 8
    private const val MAX_NAME = 64

    fun parseRows(rows: List<Map<String, String?>>): Pair<List<PcProfile>, List<String>> {
        val profiles = mutableListOf<PcProfile>()
        val skipped = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        rows.forEach { row ->
            when (val result = parseRow(row)) {
                is PcRowResult.Ok -> if (seen.add(result.profile.profileId)) profiles += result.profile else skipped += "duplicate profile_id"
                is PcRowResult.Skipped -> skipped += result.reason
            }
        }
        return profiles.sortedBy { it.pairedAtMs } to skipped
    }

    fun parseRow(row: Map<String, String?>): PcRowResult {
        val c = PcProfileContract.Columns
        val schema = row[c.SCHEMA_VERSION]?.trim()?.toIntOrNull()
            ?: return PcRowResult.Skipped("missing schema_version")
        if (schema != PcProfileContract.SCHEMA_VERSION) return PcRowResult.Skipped("unsupported schema_version $schema")

        val id = row[c.PROFILE_ID]?.trim().orEmpty()
        if (!PROFILE_ID.matches(id)) return PcRowResult.Skipped("bad profile_id")

        val name = row[c.NAME]?.trim().orEmpty()
        if (name.isEmpty() || name.codePointCount(0, name.length) > MAX_NAME || name.any { it.isISOControl() }) {
            return PcRowResult.Skipped("bad name")
        }

        val user = row[c.USER]?.trim().orEmpty()
        if (!POSIX_USER.matches(user)) return PcRowResult.Skipped("bad user")

        val hosts = splitList(row[c.HOSTS], ',')
        if (hosts.isEmpty() || hosts.size > MAX_HOSTS || hosts.any { !isHostLike(it) }) return PcRowResult.Skipped("bad hosts")

        val port = row[c.PORT]?.trim()?.toIntOrNull()
        if (port == null || port !in 1..65535) return PcRowResult.Skipped("bad port")

        val hostKeys = splitList(row[c.HOST_KEYS], ';')
        if (hostKeys.isEmpty() || hostKeys.any { !HOST_KEY.matches(it) }) return PcRowResult.Skipped("bad host_keys")

        val caps = splitList(row[c.CAPS], ',').map { it.lowercase() }.toSet()
        val pairedAt = row[c.PAIRED_AT]?.trim()?.toLongOrNull() ?: 0L
        val lastSeenHost = row[c.LAST_SEEN_HOST]?.trim()?.takeIf { it.isNotEmpty() && it in hosts }
        val lastSeenAt = row[c.LAST_SEEN_AT]?.trim()?.toLongOrNull()
        val reachable = when (row[c.REACHABLE]?.trim()?.toIntOrNull()) {
            1 -> CachedReachability.REACHABLE
            0 -> CachedReachability.UNREACHABLE
            else -> CachedReachability.UNKNOWN
        }
        val enrolled = row[c.CALLER_ENROLLED]?.trim() == "1"

        return PcRowResult.Ok(
            PcProfile(
                profileId = id,
                name = name,
                user = user,
                hosts = hosts,
                port = port,
                hostKeys = hostKeys,
                caps = caps,
                pairedAtMs = pairedAt,
                lastSeenHost = lastSeenHost,
                lastSeenAtMs = lastSeenAt,
                reachable = reachable,
                callerEnrolled = enrolled,
            ),
        )
    }

    /** §1 host-key form: `<keytype>:SHA256:<standard base64, no padding>` (SHA-256 → 43 chars). */
    private val HOST_KEY = Regex("^[a-z0-9@.-]+:SHA256:[A-Za-z0-9+/]{43}$")

    private fun splitList(raw: String?, separator: Char): List<String> =
        raw.orEmpty().split(separator).map { it.trim() }.filter { it.isNotEmpty() }

    /** IPv4, bracketed IPv6, or a DNS name. No spaces, schemes, paths or ports. */
    internal fun isHostLike(host: String): Boolean {
        if (host.length > 253) return false
        if (host.startsWith("[") && host.endsWith("]")) {
            val inner = host.substring(1, host.length - 1)
            return inner.isNotEmpty() && inner.all { it.isLetterOrDigit() || it == ':' || it == '.' || it == '%' }
        }
        return Regex("^[A-Za-z0-9]([A-Za-z0-9-]{0,62})(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,62}))*\\.?$").matches(host)
    }
}
