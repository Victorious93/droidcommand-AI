package ai.droidcommand.notes

/**
 * Contract for a notes plugin. A plugin sees the note's markdown and returns markdown. It does not
 * receive a filesystem, a network, or the vault.
 *
 * This is not an Obsidian plugin API. Obsidian plugins are not bundled, not downloaded, and not
 * loaded. The default install has zero plugins. User plugins installed through [FilePluginStore]
 * are declarative text (prepend / append / literal replace), which is the sandbox this JVM slice
 * can actually enforce. Loading arbitrary Kotlin, JavaScript or Dex is PLANNED and deliberately
 * not implemented: Android cannot safely run untrusted code in-process, and this app will not
 * pretend a catalog import is a sandbox.
 */
interface NotePlugin {
    val id: String
    val displayName: String
    val description: String

    fun transform(markdown: String): String
}

/** What the UI lists. [opCount] is how many sandboxed operations the descriptor declared. */
data class InstalledPlugin(
    val id: String,
    val displayName: String,
    val description: String,
    val enabled: Boolean,
    val opCount: Int,
)
