package ai.droidcommand.shizuku.toolbox

data class PermissionState(val name: String, val granted: Boolean, val runtime: Boolean)

/** Reads the permission lines out of `dumpsys package <pkg>`. Install-time and runtime sections are both kept. */
object PermissionParser {
    private val LINE = Regex("^\\s*([A-Za-z][A-Za-z0-9_.]+): granted=(true|false)")

    fun parse(dumpsys: String): List<PermissionState> {
        var runtime = false
        val out = linkedMapOf<String, PermissionState>()
        dumpsys.lineSequence().forEach { line ->
            val t = line.trim()
            when {
                t.startsWith("runtime permissions:") -> runtime = true
                t.startsWith("install permissions:") || t.startsWith("declared permissions:") || t.startsWith("requested permissions:") -> runtime = false
            }
            val m = LINE.find(line) ?: return@forEach
            val name = m.groupValues[1]
            out[name] = PermissionState(name, m.groupValues[2] == "true", runtime || out[name]?.runtime == true)
        }
        return out.values.sortedWith(compareBy({ !it.runtime }, { it.name }))
    }
}

/** Client-side logcat filter: keeps lines that contain [text] (case-insensitive). Blank keeps everything. */
object LogcatFilter {
    fun apply(log: String, text: String): String =
        if (text.isBlank()) log else log.lineSequence().filter { it.contains(text, ignoreCase = true) }.joinToString("\n")
}
