package ai.droidcommand.app.ui.chat

/**
 * Chat controls the owner can hide from the toolbar. Hiding a control removes its button only; it does not
 * change what the control would do (Speak set to automatic still speaks replies).
 */
enum class ToolbarToggle(val storageId: String, val label: String) {
    WEB_SEARCH("web_search", "Web search"),
    SPEAK("speak", "Speak"),
    MICROPHONE("microphone", "Dictate"),
    ATTACH("attach", "Attach document"),
}

/** Parses and serializes the saved set of visible toolbar controls. Pure, so it is JVM-tested. */
object ToolbarLayout {
    val defaults: Set<ToolbarToggle> = ToolbarToggle.entries.toSet()

    /** Null means nothing was ever saved, so every control shows. An empty string means the user hid them all. */
    fun parse(raw: String?): Set<ToolbarToggle> {
        if (raw == null) return defaults
        val ids = raw.split(',').map { it.trim() }.toSet()
        return ToolbarToggle.entries.filter { it.storageId in ids }.toSet()
    }

    /** Written in the enum's fixed order, so the same set always produces the same string. */
    fun serialize(visible: Set<ToolbarToggle>): String =
        ToolbarToggle.entries.filter { it in visible }.joinToString(",") { it.storageId }
}
