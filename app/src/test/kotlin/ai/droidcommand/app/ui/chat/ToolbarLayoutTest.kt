package ai.droidcommand.app.ui.chat

import kotlin.test.Test
import kotlin.test.assertEquals

class ToolbarLayoutTest {
    @Test
    fun `nothing saved shows every control`() {
        assertEquals(ToolbarToggle.entries.toSet(), ToolbarLayout.parse(null))
    }

    @Test
    fun `an empty saved value means the user hid every control`() {
        assertEquals(emptySet(), ToolbarLayout.parse(""))
    }

    @Test
    fun `unknown and repeated ids are ignored`() {
        assertEquals(
            setOf(ToolbarToggle.SPEAK, ToolbarToggle.MICROPHONE),
            ToolbarLayout.parse("speak,bogus,microphone,speak"),
        )
    }

    @Test
    fun `serialize uses the fixed enum order and round-trips`() {
        val visible = setOf(ToolbarToggle.ATTACH, ToolbarToggle.WEB_SEARCH)
        val raw = ToolbarLayout.serialize(visible)
        assertEquals("web_search,attach", raw)
        assertEquals(visible, ToolbarLayout.parse(raw))
    }
}
