package ai.droidcommand.skills

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * Persists each skill's enabled/disabled decision by name, so a user's
 * approvals survive a restart. It stores **only** the enabled flags — not skill
 * content — because the content comes back from [SkillDiscovery] on the next
 * launch; pairing the two (discover, install disabled, then
 * [SkillRegistry.applyEnabledState] with [load]'s result) re-hydrates the
 * registry without ever auto-enabling a skill the store does not list.
 */
interface SkillStateStore {
    /** The persisted `name -> enabled` decisions. An absent or empty store is an empty map, never an error. */
    fun load(): Map<String, Boolean>

    /** Replaces the persisted decisions with [states]. */
    fun save(states: Map<String, Boolean>)
}

/** A non-persistent [SkillStateStore], for tests and for a caller that does not want state on disk. */
class InMemorySkillStateStore(initial: Map<String, Boolean> = emptyMap()) : SkillStateStore {
    private var states: Map<String, Boolean> = initial.toMap()

    override fun load(): Map<String, Boolean> = states

    override fun save(states: Map<String, Boolean>) {
        this.states = states.toMap()
    }
}

/**
 * A file-backed [SkillStateStore] using a deliberately trivial line format,
 * `name=enabled` per line (`#` comments and blank lines ignored). JSON is
 * avoided on purpose: skill names are slugs ([SkillManifest.NAME_PATTERN], no
 * `=`, quotes, or control characters) and the values are just `true`/`false`, so
 * a line format is unambiguous and needs no parser or dependency — the same
 * dependency-light reasoning as the rest of `core-skills`.
 *
 * [load] is lenient by design: a missing file is an empty map, and a malformed
 * line or an entry whose name is not a valid slug is skipped, not thrown — a
 * corrupted store degrades to "nothing remembered" (everything disabled, the
 * safe default) rather than crashing the load. [save] writes atomically (to a
 * temp file in the same directory, then an atomic move) so a crash mid-write
 * cannot leave a half-written store, and sorts entries for a stable file.
 */
class FileSkillStateStore(private val path: Path) : SkillStateStore {
    override fun load(): Map<String, Boolean> {
        if (!path.exists()) return emptyMap()
        val text = try {
            path.readText()
        } catch (e: IOException) {
            return emptyMap()
        }
        val states = LinkedHashMap<String, Boolean>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val name = line.substring(0, eq).trim()
            if (!SkillManifest.NAME_PATTERN.matches(name)) continue
            states[name] = line.substring(eq + 1).trim() == "true"
        }
        return states
    }

    override fun save(states: Map<String, Boolean>) {
        val content = buildString {
            appendLine("# core-skills enabled-state store: one 'name=enabled' per line")
            states.toSortedMap().forEach { (name, enabled) -> appendLine("$name=$enabled") }
        }
        val parent = path.toAbsolutePath().parent
        Files.createDirectories(parent)
        val tmp = Files.createTempFile(parent, ".skill-state", ".tmp")
        try {
            Files.writeString(tmp, content)
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: IOException) {
                // ATOMIC_MOVE can be unsupported on some filesystems; fall back to a plain replace.
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }
}
