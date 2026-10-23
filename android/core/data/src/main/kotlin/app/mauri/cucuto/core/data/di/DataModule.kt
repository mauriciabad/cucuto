package app.mauri.cucuto.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import app.mauri.cucuto.core.data.db.AssignmentDao
import app.mauri.cucuto.core.data.db.ContactDao
import app.mauri.cucuto.core.data.db.CucutoDatabase
import app.mauri.cucuto.core.data.db.DecisionLogDao
import app.mauri.cucuto.core.data.db.InstalledPackDao
import app.mauri.cucuto.core.data.db.PackRepositoryDao
import app.mauri.cucuto.core.data.db.SeenSenderDao
import app.mauri.cucuto.core.data.routing.CharacterCatalog
import app.mauri.cucuto.core.data.routing.PackManifestCatalog
import app.mauri.cucuto.core.data.routing.RoutingSink
import app.mauri.cucuto.core.data.routing.XposedRoutingSink
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/** A scope that lives as long as the app process, for work such as publishing the routing table. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/** Wall-clock time in milliseconds since the epoch; replaced in tests. */
fun interface WallClock {
    fun now(): Long
}

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides
    @Singleton
    fun database(
        @ApplicationContext context: Context,
    ): CucutoDatabase = CucutoDatabase.build(context)

    @Provides fun contactDao(db: CucutoDatabase): ContactDao = db.contactDao()

    @Provides fun assignmentDao(db: CucutoDatabase): AssignmentDao = db.assignmentDao()

    @Provides fun installedPackDao(db: CucutoDatabase): InstalledPackDao = db.installedPackDao()

    @Provides fun packRepositoryDao(db: CucutoDatabase): PackRepositoryDao = db.packRepositoryDao()

    @Provides fun decisionLogDao(db: CucutoDatabase): DecisionLogDao = db.decisionLogDao()

    @Provides fun seenSenderDao(db: CucutoDatabase): SeenSenderDao = db.seenSenderDao()

    @Provides
    @Singleton
    fun settingsDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("settings") }

    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    fun wallClock(): WallClock = WallClock(System::currentTimeMillis)
}

@Module
@InstallIn(SingletonComponent::class)
interface DataBindings {
    @Binds fun routingSink(sink: XposedRoutingSink): RoutingSink

    @Binds fun characterCatalog(catalog: PackManifestCatalog): CharacterCatalog
}
