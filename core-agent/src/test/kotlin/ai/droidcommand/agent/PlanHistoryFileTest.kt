package ai.droidcommand.agent

import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlanHistoryFileTest {
    @Test
    fun `a finished run round-trips and a running run is dropped`() {
        val file = PlanHistoryFile(Files.createTempDirectory("plans").resolve("plans.json"))
        val started = Instant.parse("2026-10-12T12:00:00Z")
        val finished = started.plusSeconds(30)
        val done = PlanRun(
            id = "r1",
            objective = "list files",
            status = PlanStatus.COMPLETED,
            steps = listOf(PlanStep(1, "shell", mapOf("cmd" to "ls"), PlanStepStatus.SUCCEEDED, "a.txt")),
            summary = "listed",
            startedAt = started,
            finishedAt = finished,
        )
        val running = done.copy(id = "r2", status = PlanStatus.RUNNING, summary = null, finishedAt = null)
        file.save(listOf(running, done))
        assertEquals(listOf(done), file.load())
    }

    @Test
    fun `a corrupt file reads as empty and is not deleted`() {
        val path = Files.createTempDirectory("plans").resolve("plans.json")
        Files.writeString(path, "{not json")
        assertEquals(emptyList(), PlanHistoryFile(path).load())
        assertTrue(Files.exists(path))
    }
}
