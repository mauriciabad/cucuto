package app.mauri.cucuto.provider

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import android.os.UserManager
import android.util.Log
import app.mauri.cucuto.core.data.db.InstalledPackEntity
import app.mauri.cucuto.core.data.routing.ClipMirror
import app.mauri.cucuto.core.data.routing.ClipMirrorStore
import app.mauri.cucuto.core.data.routing.ProviderClips
import app.mauri.cucuto.core.routing.RoutingTable
import app.mauri.cucuto.diagnostics.SelfTestSound
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mirrors the clips the routing table plays into the app's media folder in shared storage,
 * `Android/media/app.mauri.cucuto/clips/` (with `.nomedia`), so SystemUI's notification player opens them as
 * `file://` paths without starting Cucuto's process (ADR 0018, #121). Shared storage is served by MediaProvider's FUSE
 * daemon, which is always running, and SystemUI holds `MANAGE_EXTERNAL_STORAGE`. Android deletes the folder with the
 * app.
 *
 * The mirror is used only when all of this holds, otherwise every clip keeps the [ClipProvider] URI:
 * - Cucuto runs as the system user, whose storage (`/storage/emulated/0`) is the one SystemUI sees;
 * - SystemUI holds `MANAGE_EXTERNAL_STORAGE` (checked once per process; `<queries>` makes SystemUI visible);
 * - shared storage is mounted.
 */
@Singleton
class MediaClipMirror
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : ClipMirror {
        private val store: ClipMirrorStore? by lazy { open() }

        override suspend fun clipUri(
            pack: InstalledPackEntity,
            characterId: String,
            file: String,
        ): String =
            withContext(Dispatchers.IO) {
                store?.mirrorClip(File(pack.path), pack.packId, pack.version, file)
            } ?: ProviderClips.clipUri(pack, characterId, file)

        override suspend fun selfTestUri(): String? =
            withContext(Dispatchers.IO) {
                val store = store ?: return@withContext null
                val chirp = SelfTestSound.ensureFile(context.filesDir) ?: return@withContext null
                store.mirror(chirp, ClipMirrorStore.SELF_TEST)
            }

        override suspend fun retain(table: RoutingTable) {
            withContext(Dispatchers.IO) { store?.retain(ClipMirrorStore.fileUris(table)) }
        }

        private fun open(): ClipMirrorStore? {
            val reason =
                when {
                    context.getSystemService(UserManager::class.java)?.isSystemUser != true -> {
                        "not the system user"
                    }

                    context.packageManager.checkPermission(MANAGE_STORAGE, SYSTEM_UI) !=
                        PackageManager.PERMISSION_GRANTED -> {
                        "SystemUI cannot read shared storage"
                    }

                    else -> {
                        null
                    }
                }
            if (reason != null) {
                Log.i(TAG, "Clip mirror off ($reason); SystemUI opens clips through the provider")
                return null
            }
            // getExternalMediaDirs is deprecated in favour of MediaStore, but it is the one app folder in shared
            // storage that needs no permission, stays out of the media library with .nomedia and goes with the app.
            @Suppress("DEPRECATION")
            val media = context.externalMediaDirs.firstOrNull()
            if (media == null || Environment.getExternalStorageState(media) != Environment.MEDIA_MOUNTED) {
                Log.i(TAG, "Clip mirror off (shared storage unavailable); SystemUI opens clips through the provider")
                return null
            }
            val store = ClipMirrorStore(File(media, DIR))
            if (!store.prepare()) {
                Log.w(TAG, "Clip mirror off (cannot create $DIR); SystemUI opens clips through the provider")
                return null
            }
            return store
        }

        private companion object {
            const val TAG = "cucuto"
            const val DIR = "clips"
            const val SYSTEM_UI = "com.android.systemui"
            const val MANAGE_STORAGE = Manifest.permission.MANAGE_EXTERNAL_STORAGE
        }
    }

@Module
@InstallIn(SingletonComponent::class)
interface ClipMirrorModule {
    @Binds fun clipMirror(mirror: MediaClipMirror): ClipMirror
}
