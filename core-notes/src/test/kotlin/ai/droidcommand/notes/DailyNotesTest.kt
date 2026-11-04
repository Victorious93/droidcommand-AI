package ai.droidcommand.notes

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DailyNotesTest {
    private val clock: Clock = Clock.fixed(Instant.parse("2026-10-30T15:00:00Z"), ZoneOffset.UTC)
    private val day: LocalDate = LocalDate.of(2026, 10, 30)

    @Test
    fun `today is Daily slash ISO date markdown`() {
        assertEquals("Daily/2026-10-30.md", DailyNotes.pathFor(day))
        assertEquals("Daily/2026-10-30.md", DailyNotes.today(clock))
        assertEquals("Daily", DailyNotes.FOLDER)
    }

    @Test
    fun `the daily template parses as a tagged note`() {
        val raw = DailyNotes.markdown(day)
        val note = NoteParser.parse(DailyNotes.pathFor(day), raw)
        assertEquals("2026-10-30", note.title)
        assertEquals(setOf("daily"), note.tags)
        assertTrue(note.body.contains("# 2026-10-30"))
        assertTrue(raw.startsWith("---"))
    }
}
