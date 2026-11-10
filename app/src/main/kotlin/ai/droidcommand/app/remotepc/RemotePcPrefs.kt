package ai.droidcommand.app.remotepc

import ai.droidcommand.remotepc.SavedPcCommand
import ai.droidcommand.remotepc.SavedPcCommands
import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * My PC tile settings: whether Don turned the tile on (off by default) and his saved commands. No key,
 * password, token or host-key material is stored here; PC profiles are re-read from VictorSuite every time.
 */
@Singleton
class RemotePcPrefs @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun enabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_ENABLED, enabled) }
    }

    fun savedCommands(): List<SavedPcCommand> = SavedPcCommands.decode(prefs.getString(KEY_COMMANDS, null))

    fun setSavedCommands(commands: List<SavedPcCommand>) {
        prefs.edit { putString(KEY_COMMANDS, SavedPcCommands.encode(commands)) }
    }

    private companion object {
        const val FILE = "remote_pc"
        const val KEY_ENABLED = "tile_enabled"
        const val KEY_COMMANDS = "saved_commands_v1"
    }
}
