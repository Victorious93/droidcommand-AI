package ai.droidcommand.app.theme

import ai.droidcommand.config.ThemePreference
import ai.droidcommand.config.ThemePreferenceCodec
import ai.droidcommand.config.ThemePreferenceStore
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

private val Context.themeDataStore by preferencesDataStore(name = "theme_prefs")

private val KEY = stringPreferencesKey("theme.preference")

/**
 * Theme choices in Jetpack DataStore. The codec and defaults are JVM-tested
 * in `core-config`; this class is the Android persistence only.
 */
@Singleton
class DataStoreThemePreferenceStore @Inject constructor(
    @ApplicationContext context: Context,
) : ThemePreferenceStore {
    private val dataStore = context.themeDataStore

    override fun load(): ThemePreference = runBlocking {
        ThemePreferenceCodec.decode(dataStore.data.first()[KEY])
    }

    override fun save(preference: ThemePreference) {
        runBlocking {
            dataStore.edit { it[KEY] = ThemePreferenceCodec.encode(preference) }
        }
    }
}
