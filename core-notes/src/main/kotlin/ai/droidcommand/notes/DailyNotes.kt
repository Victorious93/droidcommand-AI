package ai.droidcommand.notes

import java.time.Clock
import java.time.LocalDate

/**
 * One markdown file per calendar day under [FOLDER]. The file name is
 * `yyyy-MM-dd.md` so a desktop notes app that opens the same folder can
 * treat these as daily notes. The clock is injectable so tests do not
 * depend on the machine date.
 */
object DailyNotes {
    const val FOLDER = "Daily"

    fun pathFor(date: LocalDate): String = "$FOLDER/$date.md"

    fun today(clock: Clock = Clock.systemDefaultZone()): String = pathFor(LocalDate.now(clock))

    fun markdown(date: LocalDate): String {
        val iso = date.toString()
        return buildString {
            appendLine("---")
            appendLine("title: $iso")
            appendLine("tags:")
            appendLine("  - daily")
            appendLine("---")
            appendLine()
            appendLine("# $iso")
            appendLine()
        }
    }
}
