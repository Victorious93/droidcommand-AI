package ai.droidcommand.skills

/** An installed skill and whether it has been enabled (approved for use). */
data class InstalledSkill(val manifest: SkillManifest, val enabled: Boolean)

/** The outcome of an [SkillRegistry.install] call. */
sealed class InstallResult {
    data class Installed(val skill: InstalledSkill) : InstallResult()

    /** A skill with this [name] is already installed; [install] does not silently overwrite it. */
    data class Conflict(val name: String) : InstallResult()
}

/**
 * An in-memory registry of installed skills: install, enable, disable, uninstall,
 * list, and search, keyed by [SkillManifest.name].
 *
 * **Install never auto-enables.** A freshly installed skill starts `enabled =
 * false`, so importing a skill (above all an untrusted one) does nothing until a
 * user explicitly [enable]s it — the "keep execution disabled for untrusted
 * imports until approved" rule. And enabling grants no capability either: a
 * skill's [SkillManifest.declaredTools] is advisory metadata, so even an enabled
 * skill reaches a tool only through the existing capability / policy / approval /
 * grant stack, exactly as a human-driven request does. "Enabled" means the
 * skill's instructions may be surfaced to a planner; it does not hand the skill
 * shell, root, file-system, network, or remote-control access.
 *
 * Thread-safe: every read and write holds one lock, so a status screen listing
 * skills on one thread and an install/enable on another cannot observe a torn or
 * half-updated map. Insertion order is preserved ([LinkedHashMap]) so [list] is
 * stable.
 */
class SkillRegistry {
    private val lock = Any()
    private val skills = LinkedHashMap<String, InstalledSkill>()

    /** Installs [manifest] in the disabled state. Returns [InstallResult.Conflict] if a skill of that name is already installed. */
    fun install(manifest: SkillManifest): InstallResult = synchronized(lock) {
        if (skills.containsKey(manifest.name)) {
            return InstallResult.Conflict(manifest.name)
        }
        val installed = InstalledSkill(manifest = manifest, enabled = false)
        skills[manifest.name] = installed
        InstallResult.Installed(installed)
    }

    /** Enables (approves) the named skill. Returns false if no such skill is installed; idempotent otherwise. */
    fun enable(name: String): Boolean = setEnabled(name, true)

    /** Disables the named skill. Returns false if no such skill is installed; idempotent otherwise. */
    fun disable(name: String): Boolean = setEnabled(name, false)

    private fun setEnabled(name: String, enabled: Boolean): Boolean = synchronized(lock) {
        val current = skills[name] ?: return false
        skills[name] = current.copy(enabled = enabled)
        true
    }

    /** Removes the named skill entirely. Returns false if it was not installed. */
    fun uninstall(name: String): Boolean = synchronized(lock) {
        skills.remove(name) != null
    }

    /** The named skill, or null if not installed. */
    fun get(name: String): InstalledSkill? = synchronized(lock) { skills[name] }

    /** Every installed skill, in installation order. */
    fun list(): List<InstalledSkill> = synchronized(lock) { skills.values.toList() }

    /** Only the enabled skills, in installation order. */
    fun enabled(): List<InstalledSkill> = synchronized(lock) { skills.values.filter { it.enabled } }

    /**
     * Case-insensitive search over skill name and description. A blank query
     * matches everything (the same as [list]). Matches are returned in
     * installation order.
     */
    fun search(query: String): List<InstalledSkill> = synchronized(lock) {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) {
            return skills.values.toList()
        }
        skills.values.filter {
            it.manifest.name.lowercase().contains(needle) || it.manifest.description.lowercase().contains(needle)
        }
    }
}
