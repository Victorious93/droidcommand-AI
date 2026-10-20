package ai.droidcommand.remotecontrol

import ai.droidcommand.security.FleetIntegrationClass
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemoteControlStoreTest {
    @Test
    fun `json file round-trips mappings targets and macros and ignores leftover sms gates`() {
        val file = Files.createTempDirectory("remote-control").resolve("config.json")
        Files.write(
            file,
            """
            {
              "mappings": [],
              "whitelist": ["+15555550100"],
              "codeWordSha256": "should-be-ignored"
            }
            """.trimIndent().toByteArray(Charsets.UTF_8),
        )
        val store = JsonFileRemoteControlStore(file)
        val loadedLegacy = store.load()
        assertTrue(loadedLegacy.mappings.isEmpty())
        assertTrue(loadedLegacy.macros.isEmpty())
        val written = String(Files.readAllBytes(file), Charsets.UTF_8)
        assertTrue(written.contains("should-be-ignored"))

        val config = RemoteControlConfig(
            mappings = listOf(mapping(id = "a", smsCommandId = "a", voicePhrase = "alpha")),
            targets = listOf(
                TargetDevice(
                    nodeId = "victorsms-" + "ab".repeat(16),
                    displayName = "Tablet SMS",
                    integrationClass = FleetIntegrationClass.EXTERNAL_NODE,
                    packageName = RemoteCommandEnvelope.VICTOR_SMS_PACKAGE,
                ),
            ),
            macros = listOf(sampleMacro(id = "m1", voicePhrase = "run the night list")),
        )
        store.save(config)
        val loaded = store.load()
        assertEquals("a", loaded.mappings.single().id)
        assertEquals(FleetIntegrationClass.EXTERNAL_NODE, loaded.targets.single().integrationClass)
        assertEquals("m1", loaded.macros.single().id)
        val saved = String(Files.readAllBytes(file), Charsets.UTF_8)
        assertFalse(saved.contains("whitelist"))
        assertFalse(saved.contains("codeWordSha256"))
        assertFalse(saved.contains("should-be-ignored"))
    }

    @Test
    fun `duplicate mapping ids are refused`() {
        val store = InMemoryRemoteControlStore()
        assertFailsWith<IllegalArgumentException> {
            store.save(
                RemoteControlConfig(
                    mappings = listOf(
                        mapping(id = "a", smsCommandId = "one"),
                        mapping(id = "a", smsCommandId = "two"),
                    ),
                ),
            )
        }
    }

    @Test
    fun `duplicate sms commandId across mapping and macro is refused`() {
        val store = InMemoryRemoteControlStore()
        assertFailsWith<IllegalArgumentException> {
            store.save(
                RemoteControlConfig(
                    mappings = listOf(mapping(id = "a", smsCommandId = "same")),
                    macros = listOf(sampleMacro(id = "m", smsCommandId = "same")),
                ),
            )
        }
    }

    @Test
    fun `missing file loads as empty`() {
        val file = Files.createTempDirectory("remote-control").resolve("missing.json")
        assertEquals(RemoteControlConfig(), JsonFileRemoteControlStore(file).load())
    }
}
