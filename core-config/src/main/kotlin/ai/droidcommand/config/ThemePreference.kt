package ai.droidcommand.config

/**
 * Owner-facing look of the app. Defaults follow the system night setting,
 * the existing blue accent, and Material You off (it is only used on
 * Android 12+ when the owner turns it on).
 */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

enum class ThemeAccent {
    BLUE,
    TEAL,
    PURPLE,
    ORANGE,
    GREEN,
}

data class ThemePreference(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    val accent: ThemeAccent = ThemeAccent.BLUE,
    val dynamicColor: Boolean = false,
) {
    fun resolvesToDark(systemDark: Boolean): Boolean = when (mode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
}

interface ThemePreferenceStore {
    fun load(): ThemePreference
    fun save(preference: ThemePreference)
}

class InMemoryThemePreferenceStore(
    initial: ThemePreference = ThemePreference(),
) : ThemePreferenceStore {
    private var preference = initial

    @Synchronized override fun load(): ThemePreference = preference

    @Synchronized override fun save(preference: ThemePreference) {
        this.preference = preference
    }
}

class ThemeController(private val store: ThemePreferenceStore) {
    fun current(): ThemePreference = store.load()

    fun setMode(mode: ThemeMode): ThemePreference = write(current().copy(mode = mode))

    fun setAccent(accent: ThemeAccent): ThemePreference = write(current().copy(accent = accent))

    fun setDynamicColor(on: Boolean): ThemePreference = write(current().copy(dynamicColor = on))

    fun replace(preference: ThemePreference): ThemePreference = write(preference)

    private fun write(preference: ThemePreference): ThemePreference {
        store.save(preference)
        return store.load()
    }
}

object ThemePreferenceCodec {
    fun encode(preference: ThemePreference): String =
        "mode=${preference.mode.name}\naccent=${preference.accent.name}\ndynamic=${preference.dynamicColor}\n"

    fun decode(raw: String?): ThemePreference {
        if (raw.isNullOrBlank()) return ThemePreference()
        var mode = ThemeMode.SYSTEM
        var accent = ThemeAccent.BLUE
        var dynamic = false
        raw.lineSequence().forEach { line ->
            val idx = line.indexOf('=')
            if (idx <= 0) return@forEach
            val key = line.substring(0, idx).trim()
            val value = line.substring(idx + 1).trim()
            when (key) {
                "mode" -> mode = runCatching { ThemeMode.valueOf(value) }.getOrDefault(mode)
                "accent" -> accent = runCatching { ThemeAccent.valueOf(value) }.getOrDefault(accent)
                "dynamic" -> dynamic = value.equals("true", ignoreCase = true)
            }
        }
        return ThemePreference(mode, accent, dynamic)
    }
}
