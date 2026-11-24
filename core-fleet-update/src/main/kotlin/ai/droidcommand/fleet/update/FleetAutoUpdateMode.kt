package ai.droidcommand.fleet.update

/** Per-app auto-update choice. Auto-update only ever updates an app that is already installed; it never first-installs or pairs. */
enum class FleetAutoUpdateMode(val label: String) {
    OFF("Off"),
    STABLE("Stable releases"),
    INCLUDE_PRERELEASES("Include pre-releases"),
    ;

    val includesPrereleases: Boolean get() = this == INCLUDE_PRERELEASES

    companion object {
        /** Reads a stored name back. Anything unrecognised means "no choice made", not a mode. */
        fun parse(text: String?): FleetAutoUpdateMode? = entries.firstOrNull { it.name == text }
    }
}

/** Pure rules tying the global "keep fleet apps up to date" switch to the per-app choices. */
object FleetAutoUpdateRules {
    /** The mode in force for one app: its own choice if the owner made one, otherwise stable when the global switch is on, else off. */
    fun effective(stored: FleetAutoUpdateMode?, globalOn: Boolean): FleetAutoUpdateMode =
        stored ?: if (globalOn) FleetAutoUpdateMode.STABLE else FleetAutoUpdateMode.OFF

    /** The background job should exist when the global switch is on or any app has a choice other than Off. */
    fun needsSchedule(globalOn: Boolean, stored: Collection<FleetAutoUpdateMode>): Boolean =
        globalOn || stored.any { it != FleetAutoUpdateMode.OFF }
}
