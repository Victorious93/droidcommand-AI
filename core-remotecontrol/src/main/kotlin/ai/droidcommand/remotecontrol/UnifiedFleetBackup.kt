package ai.droidcommand.remotecontrol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Frozen cross-app SETTINGS/BACKUP envelope.
 * **[CONTRACT] FROZEN** — `docs/specs/UNIFIED_BACKUP.md`.
 *
 * The #233 [UnifiedLocalBackup] JSON is **one entry**
 * (`packageName = ai.droidcommand.app`). This type does not replace it.
 */
data class UnifiedFleetBackup(
    val version: Int = VERSION,
    val createdAtMs: Long,
    val sourceNodeRef: String,
    val wrapping: BackupWrapping = BackupWrapping.RAW_AES_256_GCM,
    val entries: List<UnifiedBackupEntry>,
) {
    init {
        require(sourceNodeRef.isNotBlank()) { "sourceNodeRef is required" }
        require(entries.isNotEmpty()) { "bundle needs at least one entry" }
        val packages = entries.map { it.packageName }
        require(packages.size == packages.toSet().size) { "duplicate packageName in bundle" }
        entries.forEach { it.assertMatchesWrapping(wrapping) }
    }

    companion object {
        const val VERSION = 1
        const val CONTROLLER_PACKAGE = FleetRemoteEnvelope.CONTROLLER_PACKAGE
        val SUPPORTED_VERSIONS = setOf(VERSION)
    }
}

data class UnifiedBackupEntry(
    val packageName: String,
    val schemaVersion: Int,
    val ciphertext: String? = null,
    val clearPayload: String? = null,
) {
    init {
        require(packageName.isNotBlank()) { "packageName is required" }
        require(schemaVersion > 0) { "schemaVersion must be positive" }
        val hasCipher = !ciphertext.isNullOrBlank()
        val hasClear = !clearPayload.isNullOrBlank()
        require(hasCipher xor hasClear) {
            "entry needs exactly one of ciphertext or clearPayload"
        }
    }

    fun assertMatchesWrapping(wrapping: BackupWrapping) {
        when (wrapping) {
            BackupWrapping.NONE -> require(!clearPayload.isNullOrBlank()) {
                "wrapping=none requires clearPayload (local-dev only)"
            }
            BackupWrapping.RAW_AES_256_GCM, BackupWrapping.ANDROID_KEYSTORE ->
                require(!ciphertext.isNullOrBlank()) {
                    "wrapping=${wrapping.wire} requires ciphertext"
                }
        }
    }
}

@Serializable
enum class BackupWrapping {
    @SerialName("raw-aes-256-gcm")
    RAW_AES_256_GCM,

    @SerialName("android-keystore")
    ANDROID_KEYSTORE,

    @SerialName("none")
    NONE,
    ;

    val wire: String
        get() = when (this) {
            RAW_AES_256_GCM -> "raw-aes-256-gcm"
            ANDROID_KEYSTORE -> "android-keystore"
            NONE -> "none"
        }

    companion object {
        fun fromWire(value: String): BackupWrapping? = entries.find { it.wire == value || it.name.equals(value, ignoreCase = true) }
    }
}

enum class BackupConflictPolicy(val wire: String) {
    SKIP("skip"),
    OVERWRITE("overwrite"),
    MERGE_BY_ID("merge-by-id"),
    ;

    companion object {
        fun fromWire(value: String): BackupConflictPolicy? = entries.find { it.wire == value || it.name.equals(value, ignoreCase = true) }
    }
}

data class BackupImportReport(
    val dryRun: Boolean,
    val policy: BackupConflictPolicy,
    val applied: Boolean,
    val packages: List<String>,
    val addedIds: List<String> = emptyList(),
    val skippedIds: List<String> = emptyList(),
    val overwrittenIds: List<String> = emptyList(),
    val issues: List<String> = emptyList(),
    val config: RemoteControlConfig? = null,
    val statusTray: List<NodeStatusSnapshot> = emptyList(),
    val externalControlForcedOff: Boolean = true,
)

/**
 * AES-GCM seam. Production on-device wrapping is Android Keystore
 * (documented, not implemented here). Tests use [JceFleetBackupAead] or a
 * double.
 */
interface FleetBackupAead {
    fun encrypt(plaintext: ByteArray, aad: ByteArray): String
    fun decrypt(envelope: String, aad: ByteArray): ByteArray
}

/** AES-256-GCM, 12-byte IV, 128-bit tag, `v1.<iv>.<ciphertext>` URL-safe Base64. */
class JceFleetBackupAead(
    private val key: () -> SecretKey,
) : FleetBackupAead {
    override fun encrypt(plaintext: ByteArray, aad: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        check(iv.size == GCM_IV_BYTES) { "Provider returned a ${iv.size}-byte GCM IV, expected $GCM_IV_BYTES" }
        cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(plaintext)
        val b64 = Base64.getUrlEncoder().withoutPadding()
        return listOf(VERSION, b64.encodeToString(iv), b64.encodeToString(ciphertext)).joinToString(".")
    }

    override fun decrypt(envelope: String, aad: ByteArray): ByteArray {
        val parts = envelope.split('.')
        require(parts.size == 3 && parts[0] == VERSION && parts[1].isNotEmpty() && parts[2].isNotEmpty()) {
            "malformed ciphertext envelope"
        }
        val decoder = Base64.getUrlDecoder()
        val iv = decoder.decode(parts[1])
        val ciphertext = decoder.decode(parts[2])
        require(iv.size == GCM_IV_BYTES) { "GCM IV must be $GCM_IV_BYTES bytes" }
        require(ciphertext.size >= GCM_TAG_BYTES) { "ciphertext too short for GCM tag" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val VERSION = "v1"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
        const val GCM_TAG_BYTES = 16

        fun generateKey(): SecretKey {
            val generator = KeyGenerator.getInstance("AES")
            generator.init(256)
            return generator.generateKey()
        }

        fun fromRaw(bytes: ByteArray): SecretKey {
            require(bytes.size == 32) { "AES-256 key must be 32 bytes" }
            return SecretKeySpec(bytes, "AES")
        }
    }
}

/**
 * Test double: reversible `test.<base64(aad)>.<base64(plaintext)>`. Not a
 * real cipher. Production code must use [JceFleetBackupAead].
 */
class RecordingFleetBackupAead : FleetBackupAead {
    val encryptCalls = mutableListOf<String>()

    override fun encrypt(plaintext: ByteArray, aad: ByteArray): String {
        encryptCalls += String(aad, Charsets.UTF_8)
        val b64 = Base64.getUrlEncoder().withoutPadding()
        return listOf("test", b64.encodeToString(aad), b64.encodeToString(plaintext)).joinToString(".")
    }

    override fun decrypt(envelope: String, aad: ByteArray): ByteArray {
        val parts = envelope.split('.')
        require(parts.size == 3 && parts[0] == "test") { "not a test-double envelope" }
        val decoder = Base64.getUrlDecoder()
        val boundAad = decoder.decode(parts[1])
        require(boundAad.contentEquals(aad)) { "AAD mismatch" }
        return decoder.decode(parts[2])
    }
}

object FleetBackupPrivacy {
    val FORBIDDEN_KEY_FRAGMENTS = listOf(
        "password",
        "passwd",
        "apikey",
        "api_key",
        "api-key",
        "apitoken",
        "api_token",
        "token",
        "secret",
        "codeword",
        "code_word",
        "whitelist",
        "signingkey",
        "signing_key",
        "privatekey",
        "private_key",
        "keystore",
        "cookie",
        "authorization",
        "bearer",
    )

    fun assertSafeJson(raw: String) {
        val folded = raw.lowercase()
        FORBIDDEN_KEY_FRAGMENTS.forEach { fragment ->
            val needle = "\"$fragment\""
            val alt = "\"${fragment.replace("_", "")}\""
            if (folded.contains(needle) || folded.contains(alt)) {
                throw IllegalArgumentException("backup payload must not carry '$fragment'")
            }
        }
    }
}

object UnifiedFleetBackupCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(bundle: UnifiedFleetBackup): String {
        bundle.entries.forEach { entry ->
            entry.clearPayload?.let { FleetBackupPrivacy.assertSafeJson(it) }
        }
        return json.encodeToString(UnifiedFleetBackupDto.serializer(), UnifiedFleetBackupDto.from(bundle))
    }

    fun decode(raw: String): UnifiedFleetBackup {
        val dto = try {
            json.decodeFromString(UnifiedFleetBackupDto.serializer(), raw)
        } catch (e: SerializationException) {
            throw IllegalArgumentException("Invalid UnifiedFleetBackup: ${e.message}", e)
        }
        if (dto.version !in UnifiedFleetBackup.SUPPORTED_VERSIONS) {
            throw IllegalArgumentException("unsupported backup bundle version ${dto.version}")
        }
        val wrapping = BackupWrapping.fromWire(dto.wrapping)
            ?: throw IllegalArgumentException("unknown wrapping '${dto.wrapping}'")
        val bundle = UnifiedFleetBackup(
            version = dto.version,
            createdAtMs = dto.createdAtMs,
            sourceNodeRef = dto.sourceNodeRef,
            wrapping = wrapping,
            entries = dto.entries.map { it.toModel() },
        )
        bundle.entries.forEach { entry ->
            entry.clearPayload?.let { FleetBackupPrivacy.assertSafeJson(it) }
        }
        return bundle
    }

    fun aad(sourceNodeRef: String, packageName: String, schemaVersion: Int): ByteArray =
        "$packageName|$schemaVersion|$sourceNodeRef".toByteArray(Charsets.UTF_8)

    fun aad(sourceNodeRef: String, entry: UnifiedBackupEntry): ByteArray =
        aad(sourceNodeRef, entry.packageName, entry.schemaVersion)

    fun encryptEntry(
        packageName: String,
        schemaVersion: Int,
        payload: String,
        sourceNodeRef: String,
        aead: FleetBackupAead,
    ): UnifiedBackupEntry {
        FleetBackupPrivacy.assertSafeJson(payload)
        val ciphertext = aead.encrypt(
            payload.toByteArray(Charsets.UTF_8),
            aad(sourceNodeRef, packageName, schemaVersion),
        )
        return UnifiedBackupEntry(
            packageName = packageName,
            schemaVersion = schemaVersion,
            ciphertext = ciphertext,
        )
    }

    fun decryptEntry(
        bundle: UnifiedFleetBackup,
        entry: UnifiedBackupEntry,
        aead: FleetBackupAead?,
    ): String {
        val payload = when {
            !entry.clearPayload.isNullOrBlank() -> {
                require(bundle.wrapping == BackupWrapping.NONE) {
                    "clearPayload is local-dev only (wrapping=none)"
                }
                entry.clearPayload
            }
            !entry.ciphertext.isNullOrBlank() -> {
                val cipher = aead ?: throw IllegalArgumentException("ciphertext entry needs an AEAD")
                String(cipher.decrypt(entry.ciphertext, aad(bundle.sourceNodeRef, entry)), Charsets.UTF_8)
            }
            else -> throw IllegalArgumentException("entry has no payload")
        }
        FleetBackupPrivacy.assertSafeJson(payload)
        return payload
    }

    fun controllerClearEntry(backup: UnifiedLocalBackup, sourceNodeRef: String): UnifiedFleetBackup {
        val payload = controllerPayload(backup)
        FleetBackupPrivacy.assertSafeJson(payload)
        return UnifiedFleetBackup(
            version = UnifiedFleetBackup.VERSION,
            createdAtMs = backup.exportedAtMs,
            sourceNodeRef = sourceNodeRef,
            wrapping = BackupWrapping.NONE,
            entries = listOf(
                UnifiedBackupEntry(
                    packageName = UnifiedFleetBackup.CONTROLLER_PACKAGE,
                    schemaVersion = backup.version,
                    clearPayload = payload,
                ),
            ),
        )
    }

    fun controllerEncryptedEntry(
        backup: UnifiedLocalBackup,
        sourceNodeRef: String,
        aead: FleetBackupAead,
    ): UnifiedFleetBackup {
        val payload = controllerPayload(backup)
        val entry = encryptEntry(
            packageName = UnifiedFleetBackup.CONTROLLER_PACKAGE,
            schemaVersion = backup.version,
            payload = payload,
            sourceNodeRef = sourceNodeRef,
            aead = aead,
        )
        return UnifiedFleetBackup(
            version = UnifiedFleetBackup.VERSION,
            createdAtMs = backup.exportedAtMs,
            sourceNodeRef = sourceNodeRef,
            wrapping = BackupWrapping.RAW_AES_256_GCM,
            entries = listOf(entry),
        )
    }

    internal fun controllerPayload(backup: UnifiedLocalBackup): String {
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
        return json.encodeToString(UnifiedLocalBackupDto.serializer(), UnifiedLocalBackupDto.from(backup))
    }

    internal fun parseControllerPayload(raw: String): UnifiedLocalBackup {
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
        val dto = json.decodeFromString(UnifiedLocalBackupDto.serializer(), raw)
        if (dto.version !in setOf(1, UnifiedLocalBackup.VERSION)) {
            throw IllegalArgumentException("unsupported controller backup version ${dto.version}")
        }
        return dto.toModel()
    }
}

/**
 * Import a frozen bundle onto the controller config. Never auto-enables
 * [RemoteControlConfig.externalControlEnabled].
 */
class UnifiedFleetBackupImporter {
    fun import(
        bundle: UnifiedFleetBackup,
        live: RemoteControlConfig,
        aead: FleetBackupAead? = null,
        policy: BackupConflictPolicy = BackupConflictPolicy.SKIP,
        dryRun: Boolean = false,
        packageName: String = UnifiedFleetBackup.CONTROLLER_PACKAGE,
    ): BackupImportReport {
        val entry = bundle.entries.find { it.packageName == packageName }
            ?: throw BackupImportException(RemoteErrorCategory.NOT_FOUND, "no entry for $packageName")
        val payload = UnifiedFleetBackupCodec.decryptEntry(bundle, entry, aead)
        val imported = UnifiedFleetBackupCodec.parseControllerPayload(payload)
        val merged = merge(live, imported.config, policy)
        val forced = merged.copy(externalControlEnabled = false)
        return BackupImportReport(
            dryRun = dryRun,
            policy = policy,
            applied = !dryRun,
            packages = listOf(entry.packageName),
            addedIds = diffAdded(live, forced),
            skippedIds = diffSkipped(live, imported.config, policy),
            overwrittenIds = diffOverwritten(live, imported.config, policy),
            config = if (dryRun) null else forced,
            statusTray = if (dryRun) emptyList() else imported.statusTray,
            externalControlForcedOff = true,
        )
    }

    fun merge(live: RemoteControlConfig, incoming: RemoteControlConfig, policy: BackupConflictPolicy): RemoteControlConfig {
        return live.copy(
            mappings = mergeById(live.mappings, incoming.mappings, { it.id }, policy),
            macros = mergeById(live.macros, incoming.macros, { it.id }, policy),
            pairings = mergeById(live.pairings, incoming.pairings, { it.sourceNodeRef }, policy),
            targets = mergeById(live.targets, incoming.targets, { it.nodeId }, policy),
            externalControlEnabled = false,
        )
    }

    private fun <T> mergeById(
        live: List<T>,
        incoming: List<T>,
        idOf: (T) -> String,
        policy: BackupConflictPolicy,
    ): List<T> {
        val liveById = live.associateBy(idOf)
        val incomingById = incoming.associateBy(idOf)
        return when (policy) {
            BackupConflictPolicy.SKIP, BackupConflictPolicy.MERGE_BY_ID -> {
                val keep = live.toMutableList()
                incoming.forEach { row ->
                    if (idOf(row) !in liveById) keep += row
                }
                keep
            }
            BackupConflictPolicy.OVERWRITE -> {
                val keep = live.filter { idOf(it) !in incomingById }.toMutableList()
                keep += incoming
                keep
            }
        }
    }

    private fun diffAdded(live: RemoteControlConfig, result: RemoteControlConfig): List<String> {
        val before = ids(live)
        return ids(result).filter { it !in before }
    }

    private fun diffSkipped(
        live: RemoteControlConfig,
        incoming: RemoteControlConfig,
        policy: BackupConflictPolicy,
    ): List<String> {
        if (policy == BackupConflictPolicy.OVERWRITE) return emptyList()
        val liveIds = ids(live)
        return ids(incoming).filter { it in liveIds }
    }

    private fun diffOverwritten(
        live: RemoteControlConfig,
        incoming: RemoteControlConfig,
        policy: BackupConflictPolicy,
    ): List<String> {
        if (policy != BackupConflictPolicy.OVERWRITE) return emptyList()
        val liveIds = ids(live)
        return ids(incoming).filter { it in liveIds }
    }

    private fun ids(config: RemoteControlConfig): Set<String> =
        config.mappings.map { "mapping:${it.id}" }.toSet() +
            config.macros.map { "macro:${it.id}" } +
            config.pairings.map { "pairing:${it.sourceNodeRef}" } +
            config.targets.map { "target:${it.nodeId}" }
}

class BackupImportException(
    val category: RemoteErrorCategory,
    message: String,
) : IllegalArgumentException(message)

@Serializable
internal data class UnifiedFleetBackupDto(
    val version: Int = UnifiedFleetBackup.VERSION,
    val createdAtMs: Long,
    val sourceNodeRef: String,
    val wrapping: String = BackupWrapping.RAW_AES_256_GCM.wire,
    val entries: List<UnifiedBackupEntryDto> = emptyList(),
) {
    companion object {
        fun from(bundle: UnifiedFleetBackup) = UnifiedFleetBackupDto(
            version = bundle.version,
            createdAtMs = bundle.createdAtMs,
            sourceNodeRef = bundle.sourceNodeRef,
            wrapping = bundle.wrapping.wire,
            entries = bundle.entries.map { UnifiedBackupEntryDto.from(it) },
        )
    }
}

@Serializable
internal data class UnifiedBackupEntryDto(
    val packageName: String,
    val schemaVersion: Int,
    val ciphertext: String? = null,
    val clearPayload: String? = null,
) {
    fun toModel(): UnifiedBackupEntry = UnifiedBackupEntry(
        packageName = packageName,
        schemaVersion = schemaVersion,
        ciphertext = ciphertext,
        clearPayload = clearPayload,
    )

    companion object {
        fun from(entry: UnifiedBackupEntry) = UnifiedBackupEntryDto(
            packageName = entry.packageName,
            schemaVersion = entry.schemaVersion,
            ciphertext = entry.ciphertext,
            clearPayload = entry.clearPayload,
        )
    }
}
