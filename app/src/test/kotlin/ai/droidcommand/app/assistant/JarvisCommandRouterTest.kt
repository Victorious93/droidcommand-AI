package ai.droidcommand.app.assistant

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class JarvisCommandRouterTest {
    @Test
    fun `strips wake word and routes a normal ask`() {
        val cmd = JarvisCommandRouter.route("Hey Jarvis what is the weather")
        val ask = assertIs<JarvisCommand.Ask>(cmd)
        assertEquals("what is the weather", ask.text)
    }

    @Test
    fun `open notes`() {
        val cmd = JarvisCommandRouter.route("open notes")
        assertEquals(JarvisDestination.NOTES, assertIs<JarvisCommand.Open>(cmd).destination)
    }

    @Test
    fun `note draft`() {
        val cmd = JarvisCommandRouter.route("remember that the gate code is 1234")
        val note = assertIs<JarvisCommand.SaveNote>(cmd)
        assertEquals("the gate code is 1234", note.text)
    }

    @Test
    fun `risky word needs confirmation`() {
        val cmd = JarvisCommandRouter.route("please delete all photos")
        val needs = assertIs<JarvisCommand.NeedsConfirmation>(cmd)
        assertEquals("please delete all photos", needs.text)
    }

    @Test
    fun `stop words`() {
        assertEquals(JarvisCommand.Stop, JarvisCommandRouter.route("stop"))
        assertEquals(JarvisCommand.Stop, JarvisCommandRouter.route("be quiet"))
    }

    @Test
    fun `blank after wake is nothing`() {
        assertEquals(JarvisCommand.Nothing, JarvisCommandRouter.route("hey jarvis"))
    }

    @Test
    fun `risky word matcher is whole-word`() {
        assertNull(JarvisCommandRouter.riskyWordIn("preset the volume louder"))
        assertEquals("reset", JarvisCommandRouter.riskyWordIn("please reset the phone"))
    }
}
