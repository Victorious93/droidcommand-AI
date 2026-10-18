package ai.droidcommand.config

import ai.droidcommand.security.AuditEventType
import ai.droidcommand.security.CapabilityId
import ai.droidcommand.security.InMemoryAuditLog
import ai.droidcommand.security.KeyProtectionLevel
import java.security.ProviderException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EncryptedSecretsVaultTest {
    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private val key = newKey()
    private val storage = InMemorySecretRecordStorage()
    private val audit = InMemoryAuditLog()
    private fun vault(k: SecretKey = key, s: SecretRecordStorage = storage) =
        EncryptedSecretsVault(s, JceAesGcmCipher { k }, audit)

    @Test
    fun `round trips including newlines and unicode and survives a new vault instance`() {
        val value = "sk-line1\nline2 ✓"
        vault().putSecret("openai.key", value, CapabilityId("llm.openai"))

        assertEquals(value, vault().getSecret("openai.key"))
        assertEquals(listOf("openai.key"), vault().listSecretIds(CapabilityId("llm.openai")))
        assertEquals(emptyList(), vault().listSecretIds(CapabilityId("other")))
    }

    @Test
    fun `nothing at rest contains the plaintext value or the id's value`() {
        vault().putSecret("k", "super-secret-value")

        val dump = storage.keys().joinToString { it + "=" + storage.read(it) }
        assertFalse(dump.contains("super-secret-value"))
    }

    @Test
    fun `wrong key or tampered ciphertext fail closed and report Unrecoverable`() {
        vault().putSecret("k", "v")

        val other = vault(k = newKey())
        assertNull(other.getSecret("k"))
        assertIs<SecretState.Unrecoverable>(other.inspect("k"))

        val raw = storage.read("secret.k")!!
        val parts = raw.split('.')
        val flipped = parts[2].let { (if (it[0] == 'A') "B" else "A") + it.drop(1) }
        storage.write("secret.k", "${parts[0]}.${parts[1]}.$flipped")
        assertNull(vault().getSecret("k"))
        assertIs<SecretState.Unrecoverable>(vault().inspect("k"))
    }

    @Test
    fun `a record copied under another id does not decrypt`() {
        vault().putSecret("a", "value-a")
        storage.write("secret.b", storage.read("secret.a")!!)

        assertNull(vault().getSecret("b"))
        assertIs<SecretState.Unrecoverable>(vault().inspect("b"))
        assertEquals("value-a", vault().getSecret("a"))
    }

    @Test
    fun `malformed envelopes are Unrecoverable and a missing id is Absent`() {
        for (bad in listOf("garbage", "v2.AAAA.BBBB", "v1..", "v1.AAAA.BBBB", "v1.!!.!!")) {
            storage.write("secret.x", bad)
            assertIs<SecretState.Unrecoverable>(vault().inspect("x"), bad)
            assertNull(vault().getSecret("x"))
        }
        assertIs<SecretState.Absent>(vault().inspect("never"))
    }

    @Test
    fun `revoke removes the record and a failed durable write throws without claiming success`() {
        val v = vault()
        v.putSecret("k", "v")
        v.revokeSecret("k")
        assertNull(v.getSecret("k"))
        assertIs<SecretState.Absent>(v.inspect("k"))

        val failing = object : SecretRecordStorage by InMemorySecretRecordStorage() {
            override fun write(key: String, value: String) = false
        }
        assertFailsWith<SecretStorageException> { vault(s = failing).putSecret("k", "v") }
        val throwing = object : SecretRecordStorage by InMemorySecretRecordStorage() {
            override fun read(key: String): String? = throw IllegalStateException("disk")
        }
        assertIs<SecretState.StorageUnavailable>(vault(s = throwing).inspect("k"))
    }

    @Test
    fun `invalid ids are rejected`() {
        assertFailsWith<IllegalArgumentException> { vault().putSecret(" ", "v") }
        assertFailsWith<IllegalArgumentException> { vault().putSecret("a\nb", "v") }
        assertFailsWith<IllegalArgumentException> { vault().putSecret("x".repeat(129), "v") }
    }

    @Test
    fun `audit records ids and outcomes but never values`() {
        val v = vault()
        v.putSecret("k", "the-secret", CapabilityId("cap.one"))
        v.getSecret("k")
        v.getSecret("missing")
        v.revokeSecret("k")

        val events = audit.all()
        assertEquals(listOf(AuditEventType.SECRET_ACCESSED, AuditEventType.SECRET_ACCESSED, AuditEventType.SECRET_REVOKED), events.map { it.type })
        assertTrue(events.none { it.toString().contains("the-secret") })
        assertTrue(events[0].detail.contains("capability:cap.one") && events[0].detail.endsWith("success"))
        assertTrue(events[1].detail.endsWith("not available"))
    }

    @Test
    fun `works as the vault behind VaultBackedConfigSource`() {
        val v = vault()
        v.putSecret("anthropic.key", "k1")
        val source = VaultBackedConfigSource(MapConfigSource(emptyMap()), v)

        assertEquals("k1", source.getSecretsVault().getSecret("anthropic.key"))
    }

    @Test
    fun `a keystore ProviderException on read fails closed instead of crashing`() {
        vault().putSecret("k", "v")
        val broken = EncryptedSecretsVault(storage, JceAesGcmCipher({ throw ProviderException("keystore wiped") }))

        assertNull(broken.getSecret("k"))
        assertIs<SecretState.Unrecoverable>(broken.inspect("k"))
        assertNull(broken.useSecret("k") { "called" })
        assertEquals(emptyList(), broken.listSecretIds(CapabilityId("none")))
    }

    @Test
    fun `a keystore ProviderException on write surfaces as SecretStorageException and stores nothing`() {
        val before = storage.keys()
        val broken = EncryptedSecretsVault(storage, JceAesGcmCipher({ throw ProviderException("no keystore") }))

        assertFailsWith<SecretStorageException> { broken.putSecret("k", "super-secret-value") }
        assertEquals(before, storage.keys())
    }

    @Test
    fun `useSecret hands over the value bytes and zeroes them afterwards`() {
        val v = vault()
        v.putSecret("k", "tok-é", CapabilityId("cap.one"))
        var captured: ByteArray? = null

        val seen = v.useSecret("k") { bytes ->
            captured = bytes
            String(bytes, Charsets.UTF_8)
        }

        assertEquals("tok-é", seen)
        assertTrue(captured!!.all { it == 0.toByte() })
        assertEquals("tok-é", v.getSecret("k"))
    }

    @Test
    fun `useSecret zeroes on exception and skips the block for missing or unrecoverable secrets`() {
        val v = vault()
        v.putSecret("k", "abc")
        var captured: ByteArray? = null
        assertFailsWith<IllegalStateException> {
            v.useSecret("k") { bytes ->
                captured = bytes
                error("boom")
            }
        }
        assertTrue(captured!!.all { it == 0.toByte() })

        var called = false
        assertNull(v.useSecret("missing") { called = true })
        storage.write("secret.bad", "garbage")
        assertNull(v.useSecret("bad") { called = true })
        assertFalse(called)
    }

    @Test
    fun `useSecret keeps a value that contains newlines intact and audits without the value`() {
        val v = vault()
        v.putSecret("k", "line1\nline2", CapabilityId("cap.x"))

        assertEquals("line1\nline2", v.useSecret("k") { String(it, Charsets.UTF_8) })
        v.useSecret("missing") { }

        val events = audit.all()
        assertEquals(listOf(AuditEventType.SECRET_ACCESSED, AuditEventType.SECRET_ACCESSED), events.map { it.type })
        assertTrue(events[0].detail.contains("capability:cap.x") && events[0].detail.endsWith("success"))
        assertTrue(events[1].detail.endsWith("not available"))
        assertTrue(events.none { it.toString().contains("line1") })
    }

    @Test
    fun `useSecret does not hold the vault lock while the block runs`() {
        val v = vault()
        v.putSecret("a", "va")
        v.putSecret("b", "vb")

        assertEquals("va+vb", v.useSecret("a") { a -> String(a) + "+" + v.getSecret("b") })
    }

    @Test
    fun `protection level defaults to UNKNOWN and reports what the cipher reports without throwing`() {
        assertEquals(KeyProtectionLevel.UNKNOWN, vault().protectionLevel())

        val strong = EncryptedSecretsVault(storage, JceAesGcmCipher({ key }, { KeyProtectionLevel.STRONGBOX }))
        assertEquals(KeyProtectionLevel.STRONGBOX, strong.protectionLevel())

        val failing = EncryptedSecretsVault(storage, JceAesGcmCipher({ key }, { throw ProviderException("no keystore") }))
        assertEquals(KeyProtectionLevel.UNKNOWN, failing.protectionLevel())
    }
}
