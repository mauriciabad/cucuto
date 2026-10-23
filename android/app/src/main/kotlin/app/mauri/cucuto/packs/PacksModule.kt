package app.mauri.cucuto.packs

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
interface PacksModule {
    @Binds
    fun packCatalog(library: PackLibrary): PackCatalog
}
