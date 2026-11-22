package ai.droidcommand.fleet.update

/**
 * The placeholder badge shown for an app whose real launcher icon is neither installed on the phone nor bundled with
 * DroidCommand. Two letters and a stable colour, so the list still tells apps apart. It is a placeholder, not an
 * app icon.
 */
object FleetAppMonogram {
    /** First letter or digit, plus the next capital letter after it when there is one ("VictorCam" → "VC"). */
    fun of(name: String): String {
        val first = name.indexOfFirst { it.isLetterOrDigit() }
        if (first < 0) return "?"
        val second = name.substring(first + 1).firstOrNull { it.isUpperCase() }
        return (name[first].uppercaseChar().toString() + (second?.toString() ?: ""))
    }

    /** A stable hue in 0..359 for [id]. The same id always gets the same colour. */
    fun hue(id: String): Int = id.fold(7) { h, c -> (h * 31 + c.code) and 0x7fffffff } % 360
}
