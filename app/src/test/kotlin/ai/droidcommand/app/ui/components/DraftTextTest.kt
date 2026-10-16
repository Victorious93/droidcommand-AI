package ai.droidcommand.app.ui.components

import androidx.compose.runtime.saveable.SaverScope
import kotlin.test.Test
import kotlin.test.assertEquals

class DraftTextTest {
    private val scope = SaverScope { true }

    @Test
    fun `a normal draft is saved and restored unchanged`() {
        val saved = with(BoundedTextSaver) { scope.save("a half-typed message\nwith two lines") }
        assertEquals("a half-typed message\nwith two lines", saved)
        assertEquals("a half-typed message\nwith two lines", BoundedTextSaver.restore(saved!!))
    }

    @Test
    fun `an empty draft is saved as empty`() {
        assertEquals("", with(BoundedTextSaver) { scope.save("") })
    }

    @Test
    fun `a huge paste is cut before it reaches the saved-state Bundle`() {
        val saved = with(BoundedTextSaver) { scope.save("x".repeat(MAX_SAVED_DRAFT_CHARS * 4)) }
        // Saved state shares a ~1 MB Binder budget; the cap keeps a paste from crashing the app when it is backgrounded.
        assertEquals(MAX_SAVED_DRAFT_CHARS, saved?.length)
    }

    @Test
    fun `a draft exactly at the cap is kept whole`() {
        val atCap = "y".repeat(MAX_SAVED_DRAFT_CHARS)
        assertEquals(atCap, with(BoundedTextSaver) { scope.save(atCap) })
    }
}
