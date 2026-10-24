package app.mauri.cucuto.packs

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import app.mauri.cucuto.core.pack.ImportSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Zip files handed to Cucuto by other apps: "Open with" (`ACTION_VIEW`) and the share sheet (`ACTION_SEND`), see
 * ADR 0007. The activity [offer]s each file ([importSourceOf]); the Packs screen [take]s it and imports it, so a file
 * opened while the app is elsewhere waits until the screen is up.
 */
@Singleton
class IncomingImports
    @Inject
    constructor() {
        private val _pending = MutableStateFlow<ImportSource?>(null)

        /** The file waiting to be imported, if any. */
        val pending: StateFlow<ImportSource?> = _pending.asStateFlow()

        /** Queues [source], replacing a file that was not taken yet. */
        fun offer(source: ImportSource) {
            _pending.value = source
        }

        /** Hands the waiting file over to exactly one caller. */
        fun take(): ImportSource? = _pending.getAndUpdate { null }
    }

/**
 * The file of a VIEW or SEND [intent], or null when it carries none Cucuto may read. Only the file's bytes are used:
 * nothing in the intent is forwarded or trusted, and the importer decides from the contents what the file is.
 */
fun importSourceOf(
    context: Context,
    intent: Intent?,
): ImportSource? {
    val uri =
        when (intent?.action) {
            Intent.ACTION_VIEW -> {
                intent.data
            }

            Intent.ACTION_SEND -> {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    ?: intent.clipData
                        ?.takeIf { it.itemCount > 0 }
                        ?.getItemAt(0)
                        ?.uri
            }

            else -> {
                null
            }
        } ?: return null
    if (!isAcceptable(context, uri)) return null
    return ImportSource.of(context.applicationContext.contentResolver, uri)
}

/**
 * Accepts `content:` URIs of other apps' providers only. Another app must not be able to make Cucuto read its own
 * data (its providers or private files) as a "pack" (ADR 0079). `file:` URIs are refused: without storage permission
 * Cucuto can only read its own files and world-readable system files through them, never a file another app shares.
 */
private fun isAcceptable(
    context: Context,
    uri: Uri,
): Boolean =
    uri.scheme == ContentResolver.SCHEME_CONTENT &&
        isForeignContentAuthority(uri.authority, context.packageName) { authority ->
            try {
                context.packageManager
                    .resolveContentProvider(authority, PackageManager.ComponentInfoFlags.of(0))
                    ?.packageName
            } catch (_: RuntimeException) {
                null
            }
        }

/**
 * Whether [authority] names a provider of another app (pure, unit-tested). A content URI may address another user's
 * provider as `<userId>@<authority>`, so the user prefix is dropped before the provider's package ([ownerOf]) is
 * compared with [ownPackage]. Cucuto's own authorities all start with its package name, which is checked too.
 */
internal fun isForeignContentAuthority(
    authority: String?,
    ownPackage: String,
    ownerOf: (String) -> String?,
): Boolean {
    val bare = authority?.substringAfterLast('@').orEmpty()
    if (bare.isEmpty() || bare.startsWith(OWN_AUTHORITY_PREFIX) || bare.startsWith(ownPackage)) return false
    return ownerOf(bare) != ownPackage
}

/** Every authority Cucuto declares (`app.mauri.cucuto.clips`, `.events`, `<applicationId>.creator`). */
private const val OWN_AUTHORITY_PREFIX = "app.mauri.cucuto."
