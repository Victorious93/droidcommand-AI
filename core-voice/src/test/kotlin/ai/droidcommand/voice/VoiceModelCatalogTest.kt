package ai.droidcommand.voice

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VoiceModelCatalogTest {
    @Test
    fun ids_are_unique_and_every_entry_is_a_pinned_https_archive() {
        val all = VoiceModelCatalog.all
        assertEquals(all.size, all.map { it.id }.toSet().size)
        for (m in all) {
            assertNotNull(m.archiveSha256, "${m.id} must pin its archive")
            assertTrue(m.files.all { it.url.startsWith("https://github.com/k2-fsa/sherpa-onnx/releases/download/") }, m.id)
            assertTrue(m.license.isNotBlank())
        }
    }

    @Test
    fun nothing_in_the_catalog_needs_gpl_espeak_ng_data() {
        // Owner rule 2026-10-09: no GPL. espeak-ng data is GPL-3.0; Piper/Kokoro voices need it.
        for (m in VoiceModelCatalog.all) {
            for (f in m.files) {
                val where = "${m.id}/${f.name}"
                assertFalse("espeak" in f.name.lowercase(), where)
                assertFalse("espeak" in (f.archiveEntry ?: "").lowercase(), where)
                assertFalse(f.name.lowercase().let { it.startsWith("piper") || it.contains("kokoro") }, where)
            }
            assertFalse("kokoro" in m.id || "piper" in m.id, m.id)
        }
    }

    @Test
    fun registerAll_is_idempotent() {
        val repo = VoiceModelRepository(Files.createTempDirectory("catalog").toFile()) { _, _, _, _ -> error("no network in this test") }
        VoiceModelCatalog.registerAll(repo)
        VoiceModelCatalog.registerAll(repo)
        assertEquals(VoiceModelCatalog.all.map { it.id }.toSet(), repo.list().map { it.id }.toSet())
    }

    /**
     * Opt-in live check (`VOICE_LIVE_CATALOG=1`): downloads each real catalog archive over HTTPS with the real
     * [HttpsFileDownloader], verifying the archive and every extracted file against the pinned digests. Skipped
     * by default so the normal suite needs no network and ~125 MB of downloads.
     */
    @Test
    fun live_catalog_install_matches_pinned_digests() {
        if (System.getenv("VOICE_LIVE_CATALOG") != "1") return
        val repo = VoiceModelRepository(Files.createTempDirectory("livecatalog").toFile(), HttpsFileDownloader())
        VoiceModelCatalog.registerAll(repo)
        for (m in VoiceModelCatalog.all) {
            val result = repo.install(m.id)
            assertIs<InstallResult.Installed>(result, "${m.id}: $result")
            assertIs<VoiceModelStatus.Verified>(repo.status(m.id))
            assertTrue(m.files.all { File((result as InstallResult.Installed).dir, it.name).isFile })
        }
    }
}
