package ai.droidcommand.app.di

import ai.droidcommand.app.fleetworker.FleetWorkerSettings
import ai.droidcommand.app.fleetworker.SharedPreferencesFleetWorkerSettings
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** The owner's two fleet-worker switches (both off by default). Everything else in the feature is constructor-injected. */
@Module
@InstallIn(SingletonComponent::class)
abstract class FleetWorkerModule {
    @Binds
    @Singleton
    abstract fun bindFleetWorkerSettings(impl: SharedPreferencesFleetWorkerSettings): FleetWorkerSettings
}
