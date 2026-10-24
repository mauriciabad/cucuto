package app.mauri.cucuto.core.ringtone

import app.mauri.cucuto.core.ringtone.contacts.AndroidContacts
import app.mauri.cucuto.core.ringtone.contacts.ResolverAndroidContacts
import app.mauri.cucuto.core.ringtone.render.PlatformRingRenderer
import app.mauri.cucuto.core.ringtone.render.RingFileRenderer
import app.mauri.cucuto.core.ringtone.store.InstalledRingtoneCatalog
import app.mauri.cucuto.core.ringtone.store.MediaStoreRingtoneExports
import app.mauri.cucuto.core.ringtone.store.PhoneRingtoneStore
import app.mauri.cucuto.core.ringtone.store.RingtoneCatalog
import app.mauri.cucuto.core.ringtone.store.RingtoneExports
import app.mauri.cucuto.core.ringtone.store.RoomPhoneRingtoneStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
interface RingtoneModule {
    @Binds fun androidContacts(contacts: ResolverAndroidContacts): AndroidContacts

    @Binds fun renderer(renderer: PlatformRingRenderer): RingFileRenderer

    @Binds fun catalog(catalog: InstalledRingtoneCatalog): RingtoneCatalog

    @Binds fun exports(exports: MediaStoreRingtoneExports): RingtoneExports

    @Binds fun store(store: RoomPhoneRingtoneStore): PhoneRingtoneStore
}
