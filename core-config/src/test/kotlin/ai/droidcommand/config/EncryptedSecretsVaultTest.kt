package ai.droidcommand.config

import ai.droidcommand.security.AuditEventType
import ai.droidcommand.security.CapabilityId
import ai.droidcommand.security.InMemoryAuditLog
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
}
