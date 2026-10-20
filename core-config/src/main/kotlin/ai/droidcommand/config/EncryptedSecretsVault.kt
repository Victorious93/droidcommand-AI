package ai.droidcommand.config

import ai.droidcommand.security.AuditEvent
import ai.droidcommand.security.AuditEventType
import ai.droidcommand.security.AuditLog
import ai.droidcommand.security.CapabilityId
import ai.droidcommand.security.KeyProtectionLevel
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/*
 * Design adapted from OpenDroid's KeystoreSecretStorage (Victorious93/opendroid,
 * Apache License 2.0, https://www.apache.org/licenses/LICENSE-2.0): AES-256/GCM,
 * a strictly versioned "v1.<iv>.<ciphertext>" envelope, per-record AAD binding,
 * a durable-write boolean on storage, and "cannot decrypt => treat as lost,
 * require re-entry" semantics. This is a reimplementation of that design for
 * this repository's SecretsVault contract, not a copy of its source files.
 */

/** Result of a raw encrypted-record read. No failure case carries secret material. */
sealed interface SecretState {
    /** No record exists for the id. */
    data object Absent : SecretState

    data object Present : SecretState

    /** The record is malformed, tampered with, bound to another id, or its key is gone: the user must re-enter it. */
    data object Unrecoverable : SecretState

    /** The backing storage could not be read. */
    data object StorageUnavailable : SecretState
}

/** Thrown by [EncryptedSecretsVault.putSecret] when the secret was not durably stored. Never carries the value. */
class SecretStorageException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Minimal persistence boundary; values handed to it are always ciphertext envelopes. */
interface SecretRecordStorage {
    fun read(key: String): String?

    /** Returns true only if the write is durable (e.g. SharedPreferences `commit()`, not `apply()`). */
    fun write(key: String, value: String): Boolean

    fun remove(key: String): Boolean

    fun keys(): Set<String>
}

class InMemorySecretRecordStorage : SecretRecordStorage {
    private val records = linkedMapOf<String, String>()

    @Synchronized override fun read(key: String): String? = records[key]

    @Synchronized override fun write(key: String, value: String): Boolean {
        records[key] = value
        return true
    }

    @Synchronized override fun remove(key: String): Boolean {
        records.remove(key)
        return true
    }

    @Synchronized override fun keys(): Set<String> = records.keys.toSet()
}

class EncryptedSecret(val iv: ByteArray, val ciphertext: ByteArray)

interface SecretAeadCipher {
    @Throws(GeneralSecurityException::class)
    fun encrypt(plaintext: ByteArray, aad: ByteArray): EncryptedSecret

    @Throws(GeneralSecurityException::class)
    fun decrypt(iv: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray

    /**
     * Where the encrypting key lives right now. A report, not a guarantee (see [KeyProtectionLevel]);
     * implementations that cannot tell leave this at [KeyProtectionLevel.UNKNOWN].
     */
    fun protectionLevel(): KeyProtectionLevel = KeyProtectionLevel.UNKNOWN
}

/**
 * AES-256/GCM over JCE. [key] is read on every call and never cached, so the same class serves
 * a JVM caller holding a key in memory and an Android caller returning an AndroidKeyStore
 * `SecretKey` (non-extractable, but usable with `Cipher`). The IV is always provider-generated:
 * AndroidKeyStore rejects caller-supplied IVs on encrypt, and a fresh random IV per call is the
 * safe choice everywhere.
 */
class JceAesGcmCipher(
    private val key: () -> SecretKey,
    private val protection: () -> KeyProtectionLevel,
) : SecretAeadCipher {
    /** Reports [KeyProtectionLevel.UNKNOWN]: a plain JCE key says nothing about where it lives. */
    constructor(key: () -> SecretKey) : this(key, { KeyProtectionLevel.UNKNOWN })

    override fun protectionLevel(): KeyProtectionLevel = protection()

    override fun encrypt(plaintext: ByteArray, aad: ByteArray): EncryptedSecret {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        check(iv.size == GCM_IV_BYTES) { "Provider returned a ${iv.size}-byte GCM IV, expected $GCM_IV_BYTES" }
        cipher.updateAAD(aad)
        return EncryptedSecret(iv, cipher.doFinal(plaintext))
    }

    override fun decrypt(iv: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}

internal const val GCM_IV_BYTES = 12
internal const val GCM_TAG_BYTES = 16

/** Strictly versioned `v1.<iv>.<ciphertext>` (URL-safe Base64, no padding). */
internal object SecretEnvelope {
    private const val VERSION = "v1"

    fun encode(secret: EncryptedSecret): String {
        val b64 = Base64.getUrlEncoder().withoutPadding()
        return listOf(VERSION, b64.encodeToString(secret.iv), b64.encodeToString(secret.ciphertext)).joinToString(".")
    }

    fun decode(serialized: String): EncryptedSecret? {
        val parts = serialized.split('.')
        if (parts.size != 3 || parts[0] != VERSION || parts[1].isEmpty() || parts[2].isEmpty()) return null
        val decoder = Base64.getUrlDecoder()
        return try {
            val iv = decoder.decode(parts[1])
            val ciphertext = decoder.decode(parts[2])
            if (iv.size != GCM_IV_BYTES || ciphertext.size < GCM_TAG_BYTES) null else EncryptedSecret(iv, ciphertext)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

/**
 * A [SecretsVault] that keeps every secret encrypted at rest (CAP-013 follow-up; Phase 1's
 * "API keys in a real vault"). Each record is `capabilityId '\n' value`, sealed with [cipher] and
 * authenticated against `secret:<id>` as AAD, so a ciphertext copied under a different id fails
 * to decrypt. The storage key is derived from the id, never from the value.
 *
 * Failure policy: anything that stops a record being decrypted (tamper, wrong or lost key,
 * malformed envelope, id substitution) makes [getSecret] return `null` — fail closed, never a
 * partial value — and [inspect] reports [SecretState.Unrecoverable] so a UI can ask for
 * re-entry. [putSecret] throws [SecretStorageException] if the write was not durable, rather than
 * pretending a key was saved. The audit log records ids and outcomes, never values.
 *
 * What this does not provide: protection against an attacker who can already call [getSecret] in
 * this process, or key-at-rest protection beyond what the supplied [cipher] key gives (on
 * Android that is the Keystore's job; on a plain JVM it is whatever holds the `SecretKey`).
 */
class EncryptedSecretsVault(
    private val storage: SecretRecordStorage,
    private val cipher: SecretAeadCipher,
    private val auditLog: AuditLog? = null,
) : SecretsVault {
    private class Decoded(val capabilityId: String, val value: String)

    @Synchronized
    override fun getSecret(secretId: String): String? {
        val decoded = (load(secretId) as? Loaded.Found)?.decoded
        val owner = decoded?.capabilityId?.ifEmpty { null } ?: "unscoped"
        auditLog?.record(
            AuditEvent(
                AuditEventType.SECRET_ACCESSED,
                "secret:$secretId",
                "capability:$owner accessed secret:$secretId — ${if (decoded != null) "success" else "not available"}",
            ),
        )
        return decoded?.value
    }

    @Synchronized
    override fun putSecret(secretId: String, value: String, capabilityId: CapabilityId?) {
        validateId(secretId)
        val owner = capabilityId?.value.orEmpty()
        require('\n' !in owner) { "capabilityId must not contain a newline" }
        val sealed = try {
            cipher.encrypt("$owner\n$value".toByteArray(StandardCharsets.UTF_8), aad(secretId))
        } catch (e: GeneralSecurityException) {
            throw SecretStorageException("Could not encrypt secret:$secretId", e)
        } catch (e: ProviderException) {
            throw SecretStorageException("Keystore unavailable encrypting secret:$secretId", e)
        }
        val written = try {
            storage.write(storageKey(secretId), SecretEnvelope.encode(sealed))
        } catch (e: RuntimeException) {
            throw SecretStorageException("Storage unavailable writing secret:$secretId", e)
        }
        if (!written) throw SecretStorageException("Write for secret:$secretId was not durably committed")
    }

    @Synchronized
    override fun revokeSecret(secretId: String) {
        val existed = try {
            storage.read(storageKey(secretId)) != null
        } catch (_: RuntimeException) {
            true
        }
        val removed = try {
            storage.remove(storageKey(secretId))
        } catch (e: RuntimeException) {
            throw SecretStorageException("Storage unavailable revoking secret:$secretId", e)
        }
        if (!removed) throw SecretStorageException("Revocation of secret:$secretId was not durably committed")
        if (existed) auditLog?.record(AuditEvent(AuditEventType.SECRET_REVOKED, "secret:$secretId", "revoked secret:$secretId"))
    }

    /** Ids owned by [capabilityId]. Records that cannot be decrypted are skipped (they own nothing readable). */
    @Synchronized
    override fun listSecretIds(capabilityId: CapabilityId): List<String> {
        val ids = try {
            storage.keys().filter { it.startsWith(KEY_PREFIX) }.map { it.removePrefix(KEY_PREFIX) }
        } catch (_: RuntimeException) {
            return emptyList()
        }
        return ids.filter { id -> (load(id) as? Loaded.Found)?.decoded?.capabilityId == capabilityId.value }.sorted()
    }

    /** Where the encrypting key lives. Depends on the device; see [KeyProtectionLevel]. Never touches a secret. */
    fun protectionLevel(): KeyProtectionLevel = try {
        cipher.protectionLevel()
    } catch (_: GeneralSecurityException) {
        KeyProtectionLevel.UNKNOWN
    } catch (_: RuntimeException) {
        KeyProtectionLevel.UNKNOWN
    }

    /**
     * Runs [block] with the secret's UTF-8 bytes, then zeroes them, whether or not [block] throws. Returns
     * `null` (without calling [block]) when the secret is absent or unrecoverable. Records the same audit
     * event as [getSecret].
     *
     * Unlike [getSecret] this never builds a `String` for the value: the decrypted record is wiped as soon
     * as the value bytes are split out, and those bytes are wiped when [block] returns. Do not let [block]
     * copy the array into a `String`, log it, or keep a reference; doing so defeats the point. The vault is
     * not locked while [block] runs, so [block] may be slow or call back into the vault.
     */
    fun <T> useSecret(secretId: String, block: (ByteArray) -> T): T? {
        val value = synchronized(this) { readValueBytes(secretId) } ?: return null
        try {
            return block(value)
        } finally {
            value.fill(0)
        }
    }

    private fun readValueBytes(secretId: String): ByteArray? {
        val plaintext = (loadPlaintext(secretId) as? Plain.Found)?.bytes
        val split = plaintext?.indexOf(OWNER_SEPARATOR) ?: -1
        val value = if (plaintext != null && split >= 0) plaintext.copyOfRange(split + 1, plaintext.size) else null
        val owner = if (plaintext != null && split >= 0) String(plaintext, 0, split, StandardCharsets.UTF_8).ifEmpty { null } else null
        plaintext?.fill(0)
        auditLog?.record(
            AuditEvent(
                AuditEventType.SECRET_ACCESSED,
                "secret:$secretId",
                "capability:${owner ?: "unscoped"} accessed secret:$secretId — ${if (value != null) "success" else "not available"}",
            ),
        )
        return value
    }

    /** Whether [secretId] is usable, absent, or needs re-entry — without exposing its value or writing an audit record. */
    @Synchronized
    fun inspect(secretId: String): SecretState = when (load(secretId)) {
        is Loaded.Found -> SecretState.Present
        Loaded.Missing -> SecretState.Absent
        Loaded.Unrecoverable -> SecretState.Unrecoverable
        Loaded.StorageUnavailable -> SecretState.StorageUnavailable
    }

    private sealed interface Loaded {
        class Found(val decoded: Decoded) : Loaded

        data object Missing : Loaded

        data object Unrecoverable : Loaded

        data object StorageUnavailable : Loaded
    }

    private fun load(secretId: String): Loaded = when (val plain = loadPlaintext(secretId)) {
        is Plain.Found -> {
            val bytes = plain.bytes
            try {
                val split = bytes.indexOf(OWNER_SEPARATOR)
                if (split < 0) {
                    Loaded.Unrecoverable
                } else {
                    Loaded.Found(
                        Decoded(
                            String(bytes, 0, split, StandardCharsets.UTF_8),
                            String(bytes, split + 1, bytes.size - split - 1, StandardCharsets.UTF_8),
                        ),
                    )
                }
            } finally {
                bytes.fill(0)
            }
        }
        Plain.Missing -> Loaded.Missing
        Plain.Unrecoverable -> Loaded.Unrecoverable
        Plain.StorageUnavailable -> Loaded.StorageUnavailable
    }

    private sealed interface Plain {
        class Found(val bytes: ByteArray) : Plain

        data object Missing : Plain

        data object Unrecoverable : Plain

        data object StorageUnavailable : Plain
    }

    /** Decrypts the raw `capabilityId '\n' value` bytes. The caller owns the returned array and must wipe it. */
    private fun loadPlaintext(secretId: String): Plain {
        val raw = try {
            storage.read(storageKey(secretId))
        } catch (_: RuntimeException) {
            return Plain.StorageUnavailable
        } ?: return Plain.Missing
        val envelope = SecretEnvelope.decode(raw) ?: return Plain.Unrecoverable
        return try {
            Plain.Found(cipher.decrypt(envelope.iv, envelope.ciphertext, aad(secretId)))
        } catch (_: GeneralSecurityException) {
            Plain.Unrecoverable
        } catch (_: IllegalArgumentException) {
            Plain.Unrecoverable
        } catch (_: ProviderException) {
            // AndroidKeyStore signals a broken or wiped keystore this way (it is not a GeneralSecurityException).
            // Same outcome as a lost key: fail closed, ask for re-entry. Never let it escape as a crash.
            Plain.Unrecoverable
        }
    }

    private fun validateId(secretId: String) {
        require(secretId.isNotBlank() && secretId.length <= MAX_ID_LENGTH) { "secretId must be 1..$MAX_ID_LENGTH characters" }
        require(secretId.none { it.isISOControl() }) { "secretId must not contain control characters" }
    }

    private fun storageKey(secretId: String) = KEY_PREFIX + secretId

    private fun aad(secretId: String) = "secret:$secretId".toByteArray(StandardCharsets.UTF_8)

    private companion object {
        const val OWNER_SEPARATOR = '\n'.code.toByte()
        const val KEY_PREFIX = "secret."
        const val MAX_ID_LENGTH = 128
    }
}
