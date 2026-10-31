package ai.droidcommand.app.brain

import ai.droidcommand.config.AssistantCapabilitySettingsStore
import ai.droidcommand.notes.BrainLibrarian
import ai.droidcommand.notes.NoteVault
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.file.Path
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One vault for the Brain screen and for chat retrieve/save. Default is app
 * storage (`notes-vault`). A persisted SAF tree replaces that until cleared.
 * The Kai [brainEnabled] flag is the only on-switch; it defaults off.
 */
@Singleton
class BrainRuntime @Inject constructor(
    @ApplicationContext private val context: Context,
    private val capabilities: AssistantCapabilitySettingsStore,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val appRoot: Path = context.filesDir.toPath().resolve("notes-vault")

    fun enabled(): Boolean = capabilities.load().brainEnabled

    fun setEnabled(enabled: Boolean) {
        capabilities.save(capabilities.load().copy(brainEnabled = enabled))
    }

    fun usingSaf(): Boolean = !prefs.getString(KEY_SAF, null).isNullOrBlank()

    fun safTree(): Uri? = prefs.getString(KEY_SAF, null)?.let(Uri::parse)

    fun vault(): NoteVault {
        val uri = safTree() ?: return NoteVault(appRoot)
        return NoteVault(SafVaultStorage(context.contentResolver, uri, context))
    }

    fun librarian(): BrainLibrarian = BrainLibrarian(vault())

    fun useSafFolder(uri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        prefs.edit { putString(KEY_SAF, uri.toString()) }
    }

    fun useAppFolder() {
        prefs.edit { remove(KEY_SAF) }
    }

    companion object {
        const val PREFS = "brain_prefs"
        const val KEY_SAF = "saf_tree"
    }
}
