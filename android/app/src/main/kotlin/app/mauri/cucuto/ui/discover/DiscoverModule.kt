package app.mauri.cucuto.ui.discover

import android.content.Context
import app.mauri.cucuto.core.repo.FileIndexCache
import app.mauri.cucuto.core.repo.IndexCache
import app.mauri.cucuto.core.repo.RepositoryClient
import app.mauri.cucuto.core.repo.credentials.CredentialStore
import app.mauri.cucuto.core.repo.credentials.EncryptedCredentialStore
import app.mauri.cucuto.packs.PackCatalog
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/**
 * The pack repository client and its caches (ADR 0011, ADR 0029). Everything here is created lazily, the first time
 * Discover asks: nothing touches the network or the Keystore at app start.
 */
@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {
    @Provides
    @Singleton
    fun repositoryClient(): RepositoryClient = RepositoryClient()

    @Provides
    @Singleton
    fun credentialStore(
        @ApplicationContext context: Context,
    ): CredentialStore = EncryptedCredentialStore.create(context)

    /** `cache/repo-index/`: the last copy of each index with its ETag. */
    @Provides
    @Singleton
    fun indexCache(
        @ApplicationContext context: Context,
    ): IndexCache = FileIndexCache(File(context.cacheDir, "repo-index"))

    /** `cache/repo-preview/`: covers, sprites and sample clips. */
    @Provides
    @Singleton
    fun previewStore(
        @ApplicationContext context: Context,
        client: RepositoryClient,
    ): PreviewStore = PreviewStore(client, File(context.cacheDir, "repo-preview"))

    /** `cache/repo-download/`: pack files until the importer has its own copy. */
    @Provides
    @Singleton
    fun packDownloader(
        @ApplicationContext context: Context,
        client: RepositoryClient,
        catalog: PackCatalog,
    ): PackDownloader = PackDownloader(client, catalog, File(context.cacheDir, "repo-download"))
}

@Module
@InstallIn(SingletonComponent::class)
interface DiscoverBindings {
    @Binds
    fun discoverStore(store: RoomDiscoverStore): DiscoverStore

    @Binds
    fun sampleHaptics(haptics: DeviceSampleHaptics): SampleHaptics
}
