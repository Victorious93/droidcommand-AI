package ai.droidcommand.remote.pairing

import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FilePairingStoreTest {
    private val dir = createTempDirectory("pairings-test")
    private val file = dir.resolve("nested").resolve("pairings")
    private val clock = MutableClock(Instant.parse("2026-09-27T00:00:00Z"))

    private fun registry() = PairingRegistry(clock = clock, store = FilePairingStore(file))

    @Test
    fun `pairings, expiry and revocation survive a restart`() {
        val first = registry()
        val laptop = first.pair("Laptop 💻\ttabbed")
        val tablet = first.pair("Tablet", ttl = Duration.ofHours(1))
        val old = first.pair("Old phone")
        first.revoke(old.device.id)

        val second = registry()
        assertEquals(first.list(), second.list())
        assertEquals("Laptop 💻\ttabbed", second.get(laptop.device.id)?.displayName)
        assertTrue(second.secretFor(laptop.device.id)!!.matches(laptop.secret))
        assertTrue(second.secretFor(tablet.device.id)!!.matches(tablet.secret))
        assertNull(second.secretFor(old.device.id), "a revoked pairing stays revoked after a restart")

        clock.advance(Duration.ofHours(2))
        assertNull(registry().secretFor(tablet.device.id), "an expired pairing stays expired after a restart")
    }

    @Test
    fun `the file and its directory are only accessible to their owner`() {
        registry().pair("Laptop")
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file.parent)))
        assertEquals(listOf(file), Files.list(file.parent).use { it.toList() }, "no temporary files are left behind")
    }

    @Test
    fun `the secret is never in the record's toString`() {
        val pairing = registry().pair("Laptop")
        val stored = FilePairingStore(file).load().single()
        assertFalse(stored.toString().contains(pairing.secret.encode()))
    }

    @Test
    fun `a file others can read is refused, not trusted`() {
        registry().pair("Laptop")
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"))
        val error = assertFailsWith<IOException> { registry() }
        assertTrue(error.message!!.contains("chmod 600"), error.message)
    }

    @Test
    fun `a malformed or foreign file is refused`() {
        registry().pair("Laptop")
        val good = file.readText()
        file.writeText(good + "not\tenough\tfields\n")
        assertFailsWith<IOException> { registry() }
        file.writeText("something else\n")
        assertFailsWith<IOException> { registry() }
    }

    @Test
    fun `no file yet means no pairings`() {
        assertEquals(emptyList(), registry().list())
        assertFalse(Files.exists(file))
    }

    @Test
    fun `a pairing that can't be saved is not handed out`() {
        val failing = object : PairingStore {
            override fun load() = emptyList<StoredPairing>()
            override fun save(pairings: List<StoredPairing>) = throw IOException("disk full")
        }
        val registry = PairingRegistry(store = failing)
        assertFailsWith<IOException> { registry.pair("Laptop") }
        assertEquals(emptyList(), registry.list())
    }

    @Test
    fun `a revocation takes effect even when it can't be saved`() {
        var failSaves = false
        val flaky = object : PairingStore {
            override fun load() = emptyList<StoredPairing>()
            override fun save(pairings: List<StoredPairing>) {
                if (failSaves) throw IOException("disk full")
            }
        }
        val registry = PairingRegistry(store = flaky)
        val laptop = registry.pair("Laptop")
        failSaves = true
        assertFailsWith<IOException> { registry.revoke(laptop.device.id) }
        assertNull(registry.secretFor(laptop.device.id))
        assertNotNull(registry.get(laptop.device.id)?.revokedAt)
    }
}
