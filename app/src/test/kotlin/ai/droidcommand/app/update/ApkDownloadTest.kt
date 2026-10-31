package ai.droidcommand.app.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApkDownloadTest {
    @Test
    fun `page is the releases list not GitHub Latest`() {
        assertEquals("https://github.com/Victorious93/droidcommand-AI/releases", ApkDownload.PAGE_URL)
        assertFalse(ApkDownload.PAGE_URL.endsWith("/latest"), ApkDownload.PAGE_URL)
        assertTrue(ApkDownload.PAGE_URL.startsWith("https://"))
        assertEquals("APK download page", ApkDownload.CLIP_LABEL)
    }

    @Test
    fun `version label stays plain`() {
        assertEquals("This app: 0.1.0-phase0", ApkDownload.versionLabel("0.1.0-phase0"))
        assertEquals("This app: unknown", ApkDownload.versionLabel(null))
        assertEquals("This app: unknown", ApkDownload.versionLabel("  "))
    }
}
