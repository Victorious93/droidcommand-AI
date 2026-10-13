package ai.droidcommand.app.di

import ai.droidcommand.cloudsession.CloudSessionTunnel
import ai.droidcommand.cloudsession.InMemoryCloudSessionTunnel
import ai.droidcommand.notes.FileNoteVault
import ai.droidcommand.notes.FilePluginStore
import ai.droidcommand.notes.NoteVault
import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Cloud coding tunnel and the markdown vault. The tunnel is the in-memory stub: a live remote
 * worker is PLANNED (see [ai.droidcommand.cloudsession.PLANNED_BACKEND_STATUS]). The vault and the
 * plugin directory live in app-private storage and start empty.
 */
@Module
@InstallIn(SingletonComponent::class)
object ProductModules {
    @Provides
    @Singleton
    fun provideNoteVault(@ApplicationContext context: Context): NoteVault =
        FileNoteVault(context.filesDir.toPath().resolve("notes-vault"))

    @Provides
    @Singleton
    fun providePluginStore(@ApplicationContext context: Context): FilePluginStore =
        FilePluginStore(context.filesDir.toPath().resolve("notes-vault").resolve(".plugins"))

    @Provides
    @Singleton
    fun provideCloudTunnel(): CloudSessionTunnel = InMemoryCloudSessionTunnel()
}
