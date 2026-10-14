package ai.droidcommand.coding

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class FileCodingLogTest {
    @Test
    fun `a session round-trips without Files writeString`() {
        val path = Files.createTempDirectory("coding").resolve("sessions.log")
        val session = CodingSession(
            id = "s1",
            goal = "rename the button",
            phase = CodingPhase.PLAN,
            plan = "No cloud model",
        )
        val log = FileCodingLog(path)
        log.save(listOf(session))
        assertEquals(listOf(session), log.load())
    }
}
